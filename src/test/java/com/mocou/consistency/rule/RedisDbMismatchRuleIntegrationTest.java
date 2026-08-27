package com.mocou.consistency.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.RuleStatus;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.ViolationTarget;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
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
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
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

    /** 컨슈머가 실제로 만드는 그룹 이름. 값을 옮겨 적으면 규칙과 어긋나도 이 테스트가 통과해버린다. */
    private static final String SYNC_GROUP = RedisCouponIssueSyncGateway.GROUP_NAME;

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

        redisTemplate.opsForZSet().add(CouponRedisKey.issuedMembers(COUPON_ID), "1", 1);
        redisTemplate.opsForZSet().add(CouponRedisKey.issuedMembers(COUPON_ID), "2", 2);
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

    /**
     * 재고 대조를 빼면 발급자가 없는 쿠폰에서 "검사 0건인데 위반 1건"이라는 앞뒤가 안 맞는 결과가 남는다. 발급자 수와 무관하게
     * 쿠폰마다 한 번은 대조하므로 그만큼을 함께 센다.
     */
    @Test
    @DisplayName("검사 대상은 양쪽 발급자 수에 재고 대조를 더한 값이다")
    void checkedCountSumsBothSidesAndTheStockComparison() {
        // when - Redis 2명 + DB 2명 + 재고 대조 1건
        assertThat(outcome().checkedCount()).isEqualTo(5);
    }

    /** 동기화가 끝났는데도 Redis에만 있다면 DB 적재가 유실된 것이다. */
    @Test
    @DisplayName("Redis에만 있는 발급자를 검출한다")
    void detectsMemberOnlyInRedis() {
        // given
        redisTemplate.opsForZSet().add(CouponRedisKey.issuedMembers(COUPON_ID), "3", 3);

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

    /**
     * {@code XLEN}과 별개로 {@code XPENDING}을 보는 이유를 확인한다.
     *
     * <p>컨슈머는 DB 커밋 뒤 {@code XACK}과 {@code XDEL}을 함께 하므로, 엔트리가 스트림에서 사라졌는데도 미확인으로
     * 남아 있다면 처리가 온전히 끝나지 않은 것이다. 스트림 길이만 보면 이 상태를 "동기화 완료"로 읽는다.
     *
     * <p>이 경로는 그룹 이름이 컨슈머가 만든 것과 정확히 같아야 도달한다. 어긋나면 Redis가 {@code NOGROUP} 예외를
     * 내고, 그것을 "그룹이 아직 없다"로 보는 방어 코드가 삼켜 미확인 건이 늘 0으로 나온다.
     */
    @Test
    @DisplayName("스트림은 비었지만 미확인 건이 남아 있으면 판정하지 않는다")
    void refusesToJudgeWhileEntriesAreUnacknowledged() {
        // given - 이벤트를 그룹으로 읽고 XACK 없이 엔트리만 지운다
        String streamKey = CouponRedisKey.issueStream(COUPON_ID);
        RecordId recordId =
                redisTemplate.opsForStream().add(streamKey, Map.of("memberId", "3"));
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), SYNC_GROUP);
        redisTemplate
                .opsForStream()
                .read(
                        Consumer.from(SYNC_GROUP, "test-consumer"),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        redisTemplate.opsForStream().delete(streamKey, recordId);

        // 스트림 길이로는 남은 것이 없어 보인다
        assertThat(redisTemplate.opsForStream().size(streamKey)).isZero();

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.FAILED);
        assertThat(outcome.violationCount()).isZero();
        assertThat(outcome.failureReason()).contains("처리 중이다");
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
