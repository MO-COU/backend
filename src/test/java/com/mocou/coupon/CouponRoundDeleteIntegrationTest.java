package com.mocou.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
import com.mocou.support.MySqlContainerTest;

/**
 * 회차 삭제가 지정한 회차에만 미치는지 확인한다.
 *
 * <p><b>이 테스트의 존재 이유는 지난 회차를 지키는 것이다.</b> 삭제 조건에서 {@code coupon_id}가 빠지면
 * 검증 대상인 발급 300만 건이 사라지고 복구할 방법이 전체 재적재뿐이다. 리셋은 발급만 지웠지만 삭제는
 * 회차 자체를 없애므로 위험이 더 크다. 그래서 시연 회차와 지난 회차를 함께 심고 뒤쪽이 온전한지 매번 본다.
 *
 * <p>동기화 컨슈머는 {@code mocou.issue.sync.enabled}가 기본 꺼짐이라 이 테스트 중 스트림을 건드리지 않는다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponRoundDeleteIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 28, 12, 0);
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    /** 시연 회차. 부하 테스트가 여기에 발급을 쌓았고, 이 회차를 지운다. */
    private static final long DEMO_COUPON_ID = 2;

    /** 지난 회차. 더미데이터 300만 건을 대신하며 삭제가 건드리면 안 된다. */
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

    @Autowired private CouponRoundService couponRoundService;
    @Autowired private StringRedisTemplate redisTemplate;

    /** 부하 테스트가 한 번 끝난 직후 상태를 만든다. */
    @BeforeEach
    void seedStateAfterLoadTest() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_rule_result");
        jdbcTemplate.update("DELETE FROM verification_run");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM issue_failure_log");
        jdbcTemplate.update("DELETE FROM coupon_issue_run");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        for (long memberId = 1; memberId <= 5; memberId++) {
            insertMember(memberId);
        }

        insertCoupon(PAST_COUPON_ID, "지난 회차", "CLOSED");
        insertCoupon(DEMO_COUPON_ID, "시연 회차", "OPEN");

        // 지난 회차에는 이미 발급 이력이 쌓여 있다. 삭제가 여기까지 미치면 안 된다
        insertIssue(101, PAST_COUPON_ID, 1);
        insertHistory(101);

        for (int order = 0; order < ISSUED_IN_LOAD_TEST; order++) {
            long issueId = 201 + order;
            insertIssue(issueId, DEMO_COUPON_ID, order + 2);
            insertHistory(issueId);
        }
        insertFailureLog(DEMO_COUPON_ID);
        insertNotification(DEMO_COUPON_ID);

        // 부하 테스트가 남긴 Redis 상태 — 키 8종을 모두 심어 하나라도 남는지 본다
        redisTemplate.opsForValue()
                .set(CouponRedisKey.stock(DEMO_COUPON_ID),
                        String.valueOf(TOTAL_QUANTITY - ISSUED_IN_LOAD_TEST));
        redisTemplate.opsForHash()
                .put(CouponRedisKey.metadata(DEMO_COUPON_ID), "openAtEpochSecond", "1");
        redisTemplate.opsForZSet().add(CouponRedisKey.issuedMembers(DEMO_COUPON_ID), "2", 1);
        redisTemplate.opsForHash()
                .putAll(CouponRedisKey.issueResultCounts(DEMO_COUPON_ID), Map.of("RESERVED", "3"));
        redisTemplate.opsForValue()
                .set(CouponRedisKey.issueSequence(DEMO_COUPON_ID),
                        String.valueOf(ISSUED_IN_LOAD_TEST));
        redisTemplate.opsForStream()
                .add(CouponRedisKey.issueStream(DEMO_COUPON_ID), Map.of("memberId", "9"));
        redisTemplate.opsForStream()
                .add(CouponRedisKey.issueDlqStream(DEMO_COUPON_ID), Map.of("memberId", "9"));
        redisTemplate.opsForStream()
                .add(CouponRedisKey.issueDlqFailedStream(DEMO_COUPON_ID), Map.of("memberId", "9"));
    }

    @Test
    @DisplayName("회차와 딸린 기록이 모두 사라진다")
    void deletesRoundAndEverythingUnderIt() {
        // when
        CouponRoundDeleteResult result = couponRoundService.delete(DEMO_COUPON_ID);

        // then
        assertThat(result.couponId()).isEqualTo(DEMO_COUPON_ID);
        assertThat(result.deletedIssues()).isEqualTo(ISSUED_IN_LOAD_TEST);
        assertThat(result.deletedHistories()).isEqualTo(ISSUED_IN_LOAD_TEST);
        assertThat(result.deletedFailureLogs()).isEqualTo(1);
        assertThat(result.deletedNotifications()).isEqualTo(1);

        assertThat(countIssues(DEMO_COUPON_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM coupon_stock WHERE coupon_id = " + DEMO_COUPON_ID))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM coupon WHERE coupon_id = " + DEMO_COUPON_ID))
                .isZero();
    }

    /**
     * 키 하나라도 빠지면 다음 회차가 지난 회차의 잔재를 물려받는다. 실제로
     * {@code issue-dlq-failed}가 리셋에서 빠져 있어 최종 실패 목록이 살아남았다.
     */
    @Test
    @DisplayName("Redis 키 8종이 하나도 남지 않는다")
    void removesEveryRedisKeyOfTheRound() {
        // given
        assertThat(CouponRedisKey.allIssueKeys(DEMO_COUPON_ID)).hasSize(8);

        // when
        couponRoundService.delete(DEMO_COUPON_ID);

        // then
        assertThat(CouponRedisKey.allIssueKeys(DEMO_COUPON_ID))
                .allSatisfy(key -> assertThat(redisTemplate.hasKey(key)).isFalse());
    }

    /** 삭제 조건에서 {@code coupon_id}가 빠지면 이 테스트가 걸린다. */
    @Test
    @DisplayName("지난 회차는 그대로 남는다")
    void keepsPastRoundsUntouched() {
        // when
        couponRoundService.delete(DEMO_COUPON_ID);

        // then
        assertThat(countIssues(PAST_COUPON_ID)).isEqualTo(1);
        assertThat(countHistories(PAST_COUPON_ID)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM coupon WHERE coupon_id = " + PAST_COUPON_ID))
                .isEqualTo(1);
    }

    /**
     * 검증 기록은 이 회차 것만 지운다. {@code issue_run_id}가 {@code NULL}인 실행은 더미데이터
     * 300만 건을 본 검증이라 회차와 무관하다 — 함께 지우면 그 기록까지 사라진다.
     */
    @Test
    @DisplayName("이 회차의 검증 기록만 지우고 DB 전체 대상 검증은 남긴다")
    void deletesOnlyThisRoundsVerificationRecords() {
        // given
        long issueRunId = insertIssueRun(DEMO_COUPON_ID, "SUCCESS");
        insertVerificationRun(issueRunId);
        insertVerificationRun(null); // DB 전체 대상 검증

        // when
        CouponRoundDeleteResult result = couponRoundService.delete(DEMO_COUPON_ID);

        // then
        assertThat(result.deletedVerificationRuns()).isEqualTo(1);
        assertThat(result.deletedIssueRuns()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM verification_run")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM verification_run WHERE issue_run_id IS NULL"))
                .isEqualTo(1);
    }

    /**
     * 지난 회차에는 검증 대상인 발급 300만 건이 들어 있다. 지우면 복구할 방법이 전체 재적재뿐이라
     * 지정 자체를 막는다.
     */
    @Test
    @DisplayName("종료된 회차는 지울 수 없다")
    void rejectsClosedRound() {
        // when, then
        assertThatThrownBy(() -> couponRoundService.delete(PAST_COUPON_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ROUND_NOT_DELETABLE);

        // 거부했으면 아무것도 지우지 않았어야 한다
        assertThat(countIssues(PAST_COUPON_ID)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM coupon WHERE coupon_id = " + PAST_COUPON_ID))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("없는 쿠폰을 지정하면 거부한다")
    void rejectsUnknownCoupon() {
        assertThatThrownBy(() -> couponRoundService.delete(9999))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
    }

    /**
     * 진행 중인 테스트의 대상이 삭제 뒤 동기화 대상 재도출에서 밀려나는 것을 막는다. 아래
     * {@code rejectsWhileConsumerIsProcessing}과 달리 <b>지우려는 쿠폰과 무관하게</b> 막아야 하므로
     * 다른 회차가 도는 상황으로 확인한다.
     */
    @Test
    @DisplayName("다른 회차의 부하 테스트가 진행 중이면 거부한다")
    void rejectsWhileAnyLoadTestIsRunning() {
        // given - 지난 회차에서 테스트가 도는 중
        insertIssueRun(PAST_COUPON_ID, "RUNNING");

        // when, then
        assertThatThrownBy(() -> couponRoundService.delete(DEMO_COUPON_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ROUND_NOT_DELETABLE);

        assertThat(countIssues(DEMO_COUPON_ID)).isEqualTo(ISSUED_IN_LOAD_TEST);
    }

    /**
     * 컨슈머가 읽어간 발급은 삭제가 끝난 뒤 DB에 들어오려 한다. 그러면 없는 쿠폰을 참조해 FK 위반이
     * 나고 동기화 파이프라인이 멈춘다.
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
        assertThatThrownBy(() -> couponRoundService.delete(DEMO_COUPON_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOAD_TEST_SYNC_IN_PROGRESS);

        assertThat(countIssues(DEMO_COUPON_ID)).isEqualTo(ISSUED_IN_LOAD_TEST);
    }

    /** DLQ에서 복구를 기다리는 건도 같은 이유로 막는다. */
    @Test
    @DisplayName("DLQ에서 재시도 중인 발급이 남아 있으면 거부한다")
    void rejectsWhileDlqRecoveryIsInProgress() {
        // given
        String dlqStreamKey = CouponRedisKey.issueDlqStream(DEMO_COUPON_ID);
        redisTemplate
                .opsForStream()
                .createGroup(
                        dlqStreamKey, ReadOffset.from("0"),
                        RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
        redisTemplate
                .opsForStream()
                .read(
                        Consumer.from(
                                RedisCouponIssueSyncGateway.DLQ_GROUP_NAME, "test-recovery-consumer"),
                        StreamOffset.create(dlqStreamKey, ReadOffset.lastConsumed()));

        // when, then
        assertThatThrownBy(() -> couponRoundService.delete(DEMO_COUPON_ID))
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
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                        + " VALUES (?, ?, ?)",
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

    private long insertIssueRun(long couponId, String status) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_run (coupon_id, requested_count, status, started_at)"
                        + " VALUES (?, 100, ?, ?)",
                couponId,
                status,
                Timestamp.valueOf(BASE_TIME.minusHours(1)));
        Long runId =
                jdbcTemplate.queryForObject(
                        "SELECT run_id FROM coupon_issue_run WHERE coupon_id = ?",
                        Long.class,
                        couponId);
        return runId == null ? 0 : runId;
    }

    private void insertVerificationRun(Long issueRunId) {
        jdbcTemplate.update(
                "INSERT INTO verification_run (issue_run_id, snapshot_at, verdict, started_at, finished_at)"
                        + " VALUES (?, ?, 'PASS', ?, ?)",
                issueRunId,
                Timestamp.valueOf(BASE_TIME.minusMinutes(10)),
                Timestamp.valueOf(BASE_TIME.minusMinutes(10)),
                Timestamp.valueOf(BASE_TIME.minusMinutes(8)));
    }
}
