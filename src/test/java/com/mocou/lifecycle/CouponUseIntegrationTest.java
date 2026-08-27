package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.notification.NotificationType;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponUseIntegrationTest extends CouponLifecycleIntegrationTestSupport {

    private static final long ISSUE_ID = 3001L;

    @Autowired private CouponUseService service;

    @Test
    @DisplayName("쿠폰 사용 상태와 이력을 MySQL에 저장한다")
    void storesUsedStateAndHistoryInMySql() {
        insertIssuedCoupon(ISSUE_ID);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT from_status FROM coupon_issue_history "
                                        + "WHERE coupon_issue_id = ? AND to_status = 'ISSUED'",
                                String.class,
                                ISSUE_ID))
                .isEqualTo("UNISSUED");

        CouponUseResult result = service.use(ISSUE_ID, "use-integration-1");

        assertThat(result.status()).isEqualTo(CouponIssueStatus.USED);
        assertThat(result.usedAt()).isNotNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM coupon_issue WHERE coupon_issue_id = ?",
                                String.class,
                                ISSUE_ID))
                .isEqualTo("USED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_issue_history "
                                        + "WHERE coupon_issue_id = ? AND from_status = 'ISSUED' "
                                        + "AND to_status = 'USED' AND idempotency_key = ?",
                                Integer.class,
                                ISSUE_ID,
                                "use-integration-1"))
                .isEqualTo(1);
        assertThat(usedNotificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 기준으로 만료된 쿠폰 사용을 거부한다")
    void rejectsExpiredCouponUsingDatabaseTime() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "expired-request"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.COUPON_EXPIRED));

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
        assertThat(usedNotificationCount()).isZero();
    }

    @Test
    @DisplayName("발급 쿠폰이 없으면 오류를 반환한다")
    void rejectsMissingIssue() {
        assertThatThrownBy(() -> service.use(9999L, "missing-request"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ISSUE_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 사용된 쿠폰은 다른 키로 사용할 수 없다")
    void rejectsAlreadyUsedCouponWithAnotherKey() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET status = 'USED', used_at = CURRENT_TIMESTAMP "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "another-request"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    @DisplayName("저장된 만료 상태는 만료 쿠폰 오류로 반환한다")
    void rejectsPersistedExpiredCouponAsExpiredCoupon() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET status = 'EXPIRED', "
                        + "expires_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "expired-state-request"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.COUPON_EXPIRED));
    }

    @Test
    @DisplayName("대소문자가 다른 키로는 성공 결과를 재사용하지 않는다")
    void doesNotReplaySuccessForCaseDifferentKey() {
        insertIssuedCoupon(ISSUE_ID);
        service.use(ISSUE_ID, "Case-Sensitive-Key");

        assertThatThrownBy(() -> service.use(ISSUE_ID, "case-sensitive-key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
        assertThat(usedNotificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등성 키로 재시도해도 사용 알림을 중복 기록하지 않는다")
    void doesNotDuplicateNotificationForSameKeyRetry() {
        insertIssuedCoupon(ISSUE_ID);

        CouponUseResult firstResult = service.use(ISSUE_ID, "same-key-retry");
        CouponUseResult retriedResult = service.use(ISSUE_ID, "same-key-retry");

        assertThat(retriedResult).isEqualTo(firstResult);
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
        assertThat(usedNotificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 정렬 규칙 충돌은 멱등성 충돌로 반환한다")
    void mapsCollationEquivalentHistoryKeyCollisionToConflict() {
        insertIssuedCoupon(ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "issue:" + ISSUE_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
        assertThat(usedNotificationCount()).isZero();
    }

    @Test
    @DisplayName("사용 이력 저장 실패 시 상태 변경을 롤백한다")
    void rollsBackStateWhenHistoryInsertFails() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_used_history BEFORE INSERT ON coupon_issue_history "
                        + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure'");

        assertThatThrownBy(() -> service.use(ISSUE_ID, "rollback-request"))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
        assertThat(usedNotificationCount()).isZero();
    }

    @Test
    @DisplayName("알림 큐잉 실패는 이미 성공한 것처럼 보였던 사용 처리 전체를 롤백시킨다")
    void rollsBackEntireUseWhenNotificationQueueingFails() {
        insertIssuedCoupon(ISSUE_ID);
        // outbox: 알림 큐잉이 markUsed/saveUsedHistory와 같은 트랜잭션이라, 알림 insert가
        // 실패하면 "결제/상태변경은 됐는데 알림만 실패"가 아니라 전체가 롤백돼야 한다 —
        // 이게 이전(알림 실패를 격리하던) 동작과 의도적으로 달라진 부분이다.
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_used_notification BEFORE INSERT ON notification "
                        + "FOR EACH ROW SIGNAL SQLSTATE '45000' "
                        + "SET MESSAGE_TEXT = 'forced notification failure'");

        assertThatThrownBy(() -> service.use(ISSUE_ID, "notification-failure"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_QUEUE_FAILED));

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
        assertThat(usedNotificationCount()).isZero();
    }

    @Test
    @DisplayName("같은 키의 동시 사용 요청은 같은 성공 결과를 반환한다")
    void returnsSameSuccessForConcurrentRequestsWithSameKey() throws Exception {
        insertIssuedCoupon(ISSUE_ID);

        List<Attempt> attempts =
                runConcurrently(
                        () -> attemptUse("same-key"), () -> attemptUse("same-key"));

        assertThat(attempts).allMatch(attempt -> attempt.result() != null);
        assertThat(attempts)
                .extracting(attempt -> attempt.result().usedAt())
                .containsOnly(attempts.getFirst().result().usedAt());
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
        assertThat(usedNotificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 키의 동시 요청 중 하나만 사용 처리된다")
    void allowsOnlyOneConcurrentRequestWithDifferentKeys() throws Exception {
        insertIssuedCoupon(ISSUE_ID);

        List<Attempt> attempts =
                runConcurrently(
                        () -> attemptUse("different-key-1"),
                        () -> attemptUse("different-key-2"));

        assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
        assertThat(attempts)
                .filteredOn(
                        attempt ->
                                attempt.errorCode()
                                        == ErrorCode.INVALID_STATE_TRANSITION)
                .hasSize(1);
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
        assertThat(usedNotificationCount()).isEqualTo(1);
    }

    private String statusOf(long issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM coupon_issue WHERE coupon_issue_id = ?", String.class, issueId);
    }

    private int usedHistoryCount(long issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue_history "
                        + "WHERE coupon_issue_id = ? AND to_status = 'USED'",
                Integer.class,
                issueId);
    }

    private int usedNotificationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification "
                        + "WHERE coupon_id = ? AND member_id = ? AND type = ?",
                Integer.class,
                FIXTURE_COUPON_ID,
                FIXTURE_MEMBER_ID,
                NotificationType.USED.name());
    }

    @SafeVarargs
    private List<Attempt> runConcurrently(Callable<Attempt>... calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures =
                    java.util.Arrays.stream(calls)
                            .map(
                                    call ->
                                            executor.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        start.await();
                                                        return call.call();
                                                    }))
                            .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return futures.stream()
                    .map(
                            future -> {
                                try {
                                    return future.get(10, TimeUnit.SECONDS);
                                } catch (Exception exception) {
                                    throw new IllegalStateException(exception);
                                }
                            })
                    .toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Attempt attemptUse(String idempotencyKey) {
        try {
            return new Attempt(service.use(ISSUE_ID, idempotencyKey), null);
        } catch (BusinessException exception) {
            return new Attempt(null, exception.getErrorCode());
        }
    }

    private record Attempt(CouponUseResult result, ErrorCode errorCode) {}
}
