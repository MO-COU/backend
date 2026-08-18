package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.support.MySqlContainerTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponUseIntegrationTest extends MySqlContainerTest {

    private static final long ISSUE_ID = 3001L;

    @Autowired private CouponUseService service;

    @Test
    void storesUsedStateAndHistoryInMySql() {
        insertIssuedCoupon(ISSUE_ID);

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
    }

    @Test
    void rejectsExpiredCouponUsingDatabaseTime() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "expired-request"))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(CouponUseErrorCode.COUPON_EXPIRED));

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    void rejectsMissingIssue() {
        assertThatThrownBy(() -> service.use(9999L, "missing-request"))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(CouponUseErrorCode.ISSUE_NOT_FOUND));
    }

    @Test
    void rejectsAlreadyUsedCouponWithAnotherKey() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET status = 'USED', used_at = CURRENT_TIMESTAMP "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "another-request"))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(CouponUseErrorCode.INVALID_STATE_TRANSITION));

        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    void rejectsPersistedExpiredCouponAsExpiredCoupon() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET status = 'EXPIRED', "
                        + "expires_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "expired-state-request"))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(CouponUseErrorCode.COUPON_EXPIRED));
    }

    @Test
    void doesNotReplaySuccessForCaseDifferentKey() {
        insertIssuedCoupon(ISSUE_ID);
        service.use(ISSUE_ID, "Case-Sensitive-Key");

        assertThatThrownBy(() -> service.use(ISSUE_ID, "case-sensitive-key"))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(
                                                CouponUseErrorCode.INVALID_STATE_TRANSITION));
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
    }

    @Test
    void mapsCollationEquivalentHistoryKeyCollisionToConflict() {
        insertIssuedCoupon(ISSUE_ID);

        assertThatThrownBy(() -> service.use(ISSUE_ID, "issue:" + ISSUE_ID))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(
                                                CouponUseErrorCode.IDEMPOTENCY_CONFLICT));

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    void rollsBackStateWhenHistoryInsertFails() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_used_history BEFORE INSERT ON coupon_issue_history "
                        + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure'");

        assertThatThrownBy(() -> service.use(ISSUE_ID, "rollback-request"))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(usedHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
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
    }

    @Test
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
                                        == CouponUseErrorCode.INVALID_STATE_TRANSITION)
                .hasSize(1);
        assertThat(usedHistoryCount(ISSUE_ID)).isEqualTo(1);
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
        } catch (CouponUseException exception) {
            return new Attempt(null, exception.errorCode());
        }
    }

    private record Attempt(CouponUseResult result, CouponUseErrorCode errorCode) {}
}
