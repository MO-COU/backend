package com.mocou.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 되돌리기가 시연 회차에만 미치는지 확인한다.
 *
 * <p><b>이 테스트의 존재 이유는 지난 회차를 지키는 것이다.</b> 삭제 조건에서 {@code coupon_id}가 빠지면 검증 대상인 발급
 * 300만 건이 사라지고, 되돌리려면 전체 재적재뿐이다. 그래서 시연 회차와 지난 회차를 함께 심고 뒤쪽이 온전한지 매번 본다.
 *
 * <p>동기화 컨슈머는 {@code mocou.issue.sync.enabled}가 기본 꺼짐이라 이 테스트 중 스트림을 건드리지 않는다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class LoadTestResetIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 24, 12, 0);
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    /** 시연 회차. 부하 테스트가 여기에 발급을 쌓는다. */
    private static final long DEMO_COUPON_ID = 2;

    /** 지난 회차. 더미데이터 300만 건을 대신하며 리셋이 건드리면 안 된다. */
    private static final long PAST_COUPON_ID = 1;

    private static final int TOTAL_QUANTITY = 10;
    private static final int ISSUED_IN_LOAD_TEST = 3;
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

    @Autowired private LoadTestResetService resetService;
    @Autowired private StringRedisTemplate redisTemplate;

    /** 부하 테스트가 한 번 끝난 직후 상태를 만든다. */
    @BeforeEach
    void seedStateAfterLoadTest() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_rule_result");
        jdbcTemplate.update("DELETE FROM verification_run");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM issue_failure_log");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        for (long memberId = 1; memberId <= 5; memberId++) {
            insertMember(memberId);
        }

        insertCoupon(PAST_COUPON_ID, "지난 회차", "CLOSED");
        insertCoupon(DEMO_COUPON_ID, "시연 회차", "OPEN");

        // 지난 회차에는 이미 발급 이력이 쌓여 있다. 리셋이 여기까지 지우면 안 된다
        insertIssue(101, PAST_COUPON_ID, 1);
        insertHistory(101);
        setRemainingQuantity(PAST_COUPON_ID, TOTAL_QUANTITY - 1);

        // 시연 회차는 부하 테스트로 3장이 나갔다
        for (int order = 0; order < ISSUED_IN_LOAD_TEST; order++) {
            long issueId = 201 + order;
            insertIssue(issueId, DEMO_COUPON_ID, order + 2);
            insertHistory(issueId);
        }
        setRemainingQuantity(DEMO_COUPON_ID, TOTAL_QUANTITY - ISSUED_IN_LOAD_TEST);
        insertFailureLog(DEMO_COUPON_ID);
        insertNotification(DEMO_COUPON_ID);
        insertVerificationRun();

        // 부하 테스트가 남긴 Redis 상태
        redisTemplate.opsForValue()
                .set(CouponRedisKey.stock(DEMO_COUPON_ID), String.valueOf(TOTAL_QUANTITY - ISSUED_IN_LOAD_TEST));
        redisTemplate.opsForHash().put(CouponRedisKey.metadata(DEMO_COUPON_ID), "openAtEpochSecond", "1");
        redisTemplate.opsForSet().add(CouponRedisKey.issuedMembers(DEMO_COUPON_ID), "2", "3", "4");
    }

    @Test
    @DisplayName("시연 회차의 발급과 부산물이 사라지고 재고가 되돌아온다")
    void clearsDemoRoundAndRestoresStock() {
        // when
        LoadTestResetResult result = resetService.reset();

        // then
        assertThat(result.couponId()).isEqualTo(DEMO_COUPON_ID);
        assertThat(result.deletedIssues()).isEqualTo(ISSUED_IN_LOAD_TEST);
        assertThat(result.deletedHistories()).isEqualTo(ISSUED_IN_LOAD_TEST);
        assertThat(result.deletedFailureLogs()).isEqualTo(1);
        assertThat(result.deletedNotifications()).isEqualTo(1);
        assertThat(result.restoredStock()).isEqualTo(TOTAL_QUANTITY);

        assertThat(countIssues(DEMO_COUPON_ID)).isZero();
        assertThat(remainingQuantity(DEMO_COUPON_ID)).isEqualTo(TOTAL_QUANTITY);
    }

    /** 삭제 조건에서 {@code coupon_id}가 빠지면 이 테스트가 걸린다. */
    @Test
    @DisplayName("지난 회차의 발급과 이력은 그대로 남는다")
    void keepsPastRoundsUntouched() {
        // when
        resetService.reset();

        // then
        assertThat(countIssues(PAST_COUPON_ID)).isEqualTo(1);
        assertThat(countHistories(PAST_COUPON_ID)).isEqualTo(1);
        assertThat(remainingQuantity(PAST_COUPON_ID)).isEqualTo(TOTAL_QUANTITY - 1);
    }

    @Test
    @DisplayName("검증 기록은 전부 사라진다")
    void clearsVerificationRecords() {
        // when
        LoadTestResetResult result = resetService.reset();

        // then - 어느 검증이 이 회차를 본 것인지 골라낼 수 없어 전부 지운다
        assertThat(result.deletedVerificationRuns()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM verification_run")).isZero();
    }

    @Test
    @DisplayName("Redis 키가 지워지고 DB 재고로 다시 세워진다")
    void rebuildsRedisFromDatabase() {
        // when
        resetService.reset();

        // then - 초기화 스크립트가 기존 키를 덮어쓰지 않으므로 먼저 지워야 이 값이 나온다
        assertThat(redisTemplate.opsForValue().get(CouponRedisKey.stock(DEMO_COUPON_ID)))
                .isEqualTo(String.valueOf(TOTAL_QUANTITY));
        // 다음 부하 테스트에서 같은 회원이 중복으로 걸리지 않아야 한다
        assertThat(redisTemplate.hasKey(CouponRedisKey.issuedMembers(DEMO_COUPON_ID))).isFalse();
    }

    @Test
    @DisplayName("발급을 여는 쿠폰이 없으면 거부한다")
    void rejectsWhenNoOpenCoupon() {
        // given
        jdbcTemplate.update("UPDATE coupon SET status = 'CLOSED' WHERE coupon_id = ?", DEMO_COUPON_ID);

        // when, then
        assertThatThrownBy(() -> resetService.reset())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOAD_TEST_TARGET_NOT_UNIQUE);
    }

    @Test
    @DisplayName("발급을 여는 쿠폰이 둘 이상이면 거부한다")
    void rejectsWhenMultipleOpenCoupons() {
        // given - 어느 쪽을 되돌릴지 서버가 정할 수 없다
        jdbcTemplate.update("UPDATE coupon SET status = 'OPEN' WHERE coupon_id = ?", PAST_COUPON_ID);

        // when, then
        assertThatThrownBy(() -> resetService.reset())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOAD_TEST_TARGET_NOT_UNIQUE);

        // 거부했으면 아무것도 지우지 않았어야 한다
        assertThat(countIssues(DEMO_COUPON_ID)).isEqualTo(ISSUED_IN_LOAD_TEST);
    }

    /**
     * 컨슈머가 읽어간 발급은 리셋이 끝난 뒤 DB에 들어올 수 있다. 그러면 방금 지운 발급이 되살아난다.
     *
     * <p>스트림에 남아 있기만 한 이벤트는 막지 않는다. 어차피 키째로 지우기 때문이다.
     */
    @Test
    @DisplayName("컨슈머가 처리 중인 발급이 남아 있으면 거부한다")
    void rejectsWhileConsumerIsProcessing() {
        // given - 그룹으로 읽고 XACK 없이 엔트리만 지운다
        String streamKey = CouponRedisKey.issueStream(DEMO_COUPON_ID);
        RecordId recordId = redisTemplate.opsForStream().add(streamKey, Map.of("memberId", "5"));
        redisTemplate
                .opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), RedisCouponIssueSyncGateway.GROUP_NAME);
        redisTemplate
                .opsForStream()
                .read(
                        Consumer.from(RedisCouponIssueSyncGateway.GROUP_NAME, "test-consumer"),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        redisTemplate.opsForStream().delete(streamKey, recordId);

        // when, then
        assertThatThrownBy(() -> resetService.reset())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOAD_TEST_SYNC_IN_PROGRESS);

        assertThat(countIssues(DEMO_COUPON_ID)).isEqualTo(ISSUED_IN_LOAD_TEST);
    }

    private long countIssues(long couponId) {
        return count("SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = " + couponId);
    }

    private long countHistories(long couponId) {
        return count(
                """
                SELECT COUNT(*) FROM coupon_issue_history h
                JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
                WHERE i.coupon_id = %d
                """
                        .formatted(couponId));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int remainingQuantity(long couponId) {
        Integer value =
                jdbcTemplate.queryForObject(
                        "SELECT remaining_quantity FROM coupon_stock WHERE coupon_id = ?",
                        Integer.class,
                        couponId);
        return value == null ? 0 : value;
    }

    private void setRemainingQuantity(long couponId, int remaining) {
        jdbcTemplate.update(
                "UPDATE coupon_stock SET remaining_quantity = ? WHERE coupon_id = ?", remaining, couponId);
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

    private void insertCoupon(long couponId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                couponId,
                name,
                Timestamp.valueOf(BASE_TIME.minusDays(1)),
                Timestamp.valueOf(BASE_TIME.plusDays(365)),
                status,
                Timestamp.valueOf(BASE_TIME.minusDays(1)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (?, ?, ?)",
                couponId,
                TOTAL_QUANTITY,
                TOTAL_QUANTITY);
    }

    private void insertIssue(long issueId, long couponId, long memberId) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, ?, ?, 'ISSUED', ?, NULL, ?)",
                issueId,
                couponId,
                memberId,
                Timestamp.valueOf(BASE_TIME.minusHours(1)),
                Timestamp.valueOf(NEVER_EXPIRES));
    }

    private void insertHistory(long issueId) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history"
                        + " (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)"
                        + " VALUES (?, 'UNISSUED', 'ISSUED', ?, ?)",
                issueId,
                Timestamp.valueOf(BASE_TIME.minusHours(1)),
                "issue-" + issueId);
    }

    private void insertFailureLog(long couponId) {
        jdbcTemplate.update(
                "INSERT INTO issue_failure_log (coupon_id, member_id, failure_reason, occurred_at)"
                        + " VALUES (?, 5, 'SOLD_OUT', ?)",
                couponId,
                Timestamp.valueOf(BASE_TIME.minusHours(1)));
    }

    private void insertNotification(long couponId) {
        jdbcTemplate.update(
                "INSERT INTO notification (coupon_id, member_id, type, status, sent_at, created_at)"
                        + " VALUES (?, 2, 'ISSUE_COMPLETED', 'SENT', ?, ?)",
                couponId,
                Timestamp.valueOf(BASE_TIME.minusHours(1)),
                Timestamp.valueOf(BASE_TIME.minusHours(1)));
    }

    private void insertVerificationRun() {
        jdbcTemplate.update(
                "INSERT INTO verification_run (issue_run_id, snapshot_at, verdict, started_at, finished_at)"
                        + " VALUES (NULL, ?, 'PASS', ?, ?)",
                Timestamp.valueOf(BASE_TIME.minusMinutes(10)),
                Timestamp.valueOf(BASE_TIME.minusMinutes(10)),
                Timestamp.valueOf(BASE_TIME.minusMinutes(8)));
    }
}
