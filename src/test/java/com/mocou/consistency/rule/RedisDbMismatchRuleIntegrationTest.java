package com.mocou.consistency.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.RuleStatus;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.ViolationTarget;
import com.mocou.issue.CouponRedisKey;
import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis와 MySQL을 함께 띄워 교차 검증 규칙을 확인한다.
 *
 * <p>동기화 컨슈머는 {@code mocou.issue.sync.enabled}가 기본 꺼짐이라 이 테스트 중에 스트림을 건드리지 않는다. 덕분에
 * "아직 동기화되지 않은 상태"를 직접 만들어 볼 수 있다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class RedisDbMismatchRuleIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 23, 12, 0);
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);
    private static final long COUPON_ID = 1;
    private static final int TOTAL_QUANTITY = 10;
    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired private List<ConsistencyRule> rules;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    private VerificationContext context;

    @BeforeEach
    void seedMatchingState() {
        context = new VerificationContext(BASE_TIME, 300, 1_000);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        insertMember(1);
        insertMember(2);
        insertMember(3);
        insertCoupon();

        // 회원 1·2가 발급받았고 DB에도 반영된 상태. 재고 10에서 2장이 나갔다.
        insertIssue(1, 1);
        insertIssue(2, 2);
        jdbcTemplate.update(
                "UPDATE coupon_stock SET remaining_quantity = ? WHERE coupon_id = ?",
                TOTAL_QUANTITY - 2,
                COUPON_ID);

        redisTemplate.opsForSet().add(CouponRedisKey.issuedMembers(COUPON_ID), "1", "2");
        redisTemplate.opsForValue().set(CouponRedisKey.stock(COUPON_ID), String.valueOf(TOTAL_QUANTITY - 2));
    }

    @Test
    @DisplayName("Redis와 DB가 일치하면 위반이 없다")
    void passesWhenBothSidesMatch() {
        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.CHECKED);
        assertThat(outcome.violationCount()).isZero();
    }

    @Test
    @DisplayName("검사 대상은 양쪽 발급자 수를 합산한다")
    void checkedCountSumsBothSides() {
        // when - Redis 2명 + DB 2명
        assertThat(outcome().checkedCount()).isEqualTo(4);
    }

    /** 동기화가 끝났는데도 Redis에만 있다면 DB 적재가 유실된 것이다. */
    @Test
    @DisplayName("Redis에만 있는 발급자를 검출한다")
    void detectsMemberOnlyInRedis() {
        // given
        redisTemplate.opsForSet().add(CouponRedisKey.issuedMembers(COUPON_ID), "3");

        // when
        RuleOutcome outcome = outcome();

        // then - 발급자 차이 1건 + 재고 불일치 1건
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetType())
                                    .isEqualTo(ViolationTarget.COUPON_MEMBER_PAIR);
                            assertThat(violation.targetId2()).isEqualTo(3);
                            assertThat(violation.detail()).contains("ISSUED_ONLY_IN_REDIS");
                        });
    }

    @Test
    @DisplayName("DB에만 있는 발급자를 검출한다")
    void detectsMemberOnlyInDb() {
        // given - 발급하지 않은 회원의 이력만 DB에 넣는다
        insertIssue(3, 3);

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetId2()).isEqualTo(3);
                            assertThat(violation.detail()).contains("ISSUED_ONLY_IN_DB");
                        });
    }

    @Test
    @DisplayName("Redis 재고와 DB 잔여 재고가 다르면 검출한다")
    void detectsStockMismatch() {
        // given
        redisTemplate.opsForValue().set(CouponRedisKey.stock(COUPON_ID), "5");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON);
                            assertThat(violation.detail())
                                    .contains("STOCK_COUNT_MISMATCH", "Redis 5", "DB 8");
                        });
    }

    @Test
    @DisplayName("Redis 재고 키가 없으면 검출한다")
    void detectsMissingStockKey() {
        // given
        redisTemplate.delete(CouponRedisKey.stock(COUPON_ID));

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(violation -> assertThat(violation.detail()).contains("Redis 없음"));
    }

    /**
     * 발급이 아직 DB로 넘어가지 않은 상태에서는 판정하지 않는다. 이때 위반 0건으로 통과시키면 "검증했는데 문제없다"는 거짓 신호가 된다.
     */
    @Test
    @DisplayName("동기화되지 않은 발급 이벤트가 남아 있으면 판정하지 않는다")
    void refusesToJudgeWhileEventsArePending() {
        // given - 컨슈머가 아직 처리하지 못한 이벤트
        redisTemplate
                .opsForStream()
                .add(CouponRedisKey.issueStream(COUPON_ID), Map.of("memberId", "3"));

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.FAILED);
        assertThat(outcome.violationCount()).isZero();
        assertThat(outcome.failureReason()).contains("동기화되지 않았다");
    }

    @Test
    @DisplayName("발급을 여는 쿠폰이 없으면 검사 대상이 없다")
    void skipsWhenNoOpenCoupon() {
        // given
        jdbcTemplate.update("UPDATE coupon SET status = 'CLOSED' WHERE coupon_id = ?", COUPON_ID);

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.CHECKED);
        assertThat(outcome.checkedCount()).isZero();
    }

    private RuleOutcome outcome() {
        return rules.stream()
                .filter(rule -> rule.rule() == VerificationRule.REDIS_DB_MISMATCH)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("규칙 구현이 없다"))
                .check(namedJdbcTemplate, context);
    }

    private void insertMember(long memberId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                memberId,
                "user%d@mocou.test".formatted(memberId),
                "회원" + memberId,
                "010-0000-000%d".formatted(memberId),
                Timestamp.valueOf(BASE_TIME.minusYears(1)));
    }

    private void insertCoupon() {
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (?, '시연 쿠폰', ?, ?, 'OPEN', ?)",
                COUPON_ID,
                Timestamp.valueOf(BASE_TIME.minusDays(1)),
                Timestamp.valueOf(BASE_TIME.plusDays(365)),
                Timestamp.valueOf(BASE_TIME.minusDays(1)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (?, ?, ?)",
                COUPON_ID,
                TOTAL_QUANTITY,
                TOTAL_QUANTITY);
    }

    private void insertIssue(long issueId, long memberId) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, ?, ?, 'ISSUED', ?, NULL, ?)",
                issueId,
                COUPON_ID,
                memberId,
                Timestamp.valueOf(BASE_TIME.minusHours(1)),
                Timestamp.valueOf(NEVER_EXPIRES));
    }
}
