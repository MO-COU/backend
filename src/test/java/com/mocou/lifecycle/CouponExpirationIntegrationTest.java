package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponExpirationIntegrationTest extends CouponLifecycleIntegrationTestSupport {

    private static final long ISSUE_ID = 3001L;

    @Autowired private CouponExpirationService service;
    @Autowired private CouponUseService couponUseService;
    @Autowired private CouponExpirationRepository repository;

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_expired_history");
    }

    @Test
    @DisplayName("만료 대상은 한 번만 만료 처리되고 이력이 기록된다")
    void expiresDueIssueAndRecordsHistoryOnlyOnce() {
        insertIssuedCoupon(ISSUE_ID);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 18, 17, 0);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = ? WHERE coupon_issue_id = ?", expiresAt, ISSUE_ID);

        service.expireDueIssues(LocalDateTime.of(2026, 8, 18, 18, 0), 1000);
        service.expireDueIssues(LocalDateTime.of(2026, 8, 18, 18, 0), 1000);

        assertThat(statusOf(ISSUE_ID)).isEqualTo("EXPIRED");
        assertThat(expiredHistoryCount(ISSUE_ID)).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT idempotency_key FROM coupon_issue_history "
                                        + "WHERE coupon_issue_id = ? AND to_status = 'EXPIRED'",
                                String.class,
                                ISSUE_ID))
                .isEqualTo("EXPIRE:3001:2026-08-18T17:00");
    }

    @Test
    @DisplayName("만료 기준 시각 이후의 쿠폰은 발급 상태로 유지된다")
    void leavesIssueWithExpiryAfterCutoffIssued() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = ? WHERE coupon_issue_id = ?",
                LocalDateTime.of(2026, 8, 18, 18, 0, 1),
                ISSUE_ID);

        service.expireDueIssues(LocalDateTime.of(2026, 8, 18, 18, 0), 1000);

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(expiredHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    @DisplayName("만료 이력 저장에 실패하면 상태 변경을 롤백한다")
    void rollsBackStatusWhenExpiredHistoryInsertFails() {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = ? WHERE coupon_issue_id = ?",
                LocalDateTime.of(2026, 8, 18, 17, 0),
                ISSUE_ID);
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_expired_history BEFORE INSERT ON coupon_issue_history "
                        + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure'");

        assertThatThrownBy(
                        () -> service.expireDueIssues(LocalDateTime.of(2026, 8, 18, 18, 0), 1000))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(ISSUE_ID)).isEqualTo("ISSUED");
        assertThat(expiredHistoryCount(ISSUE_ID)).isZero();
    }

    @Test
    @DisplayName("만료 후보는 만료 시각과 쿠폰 발급 ID 순으로 조회한다")
    void findsDueIssuesByExpirationTimeThenIssueId() {
        // given
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = ? WHERE coupon_issue_id = ?",
                LocalDateTime.of(2026, 8, 18, 17, 30),
                ISSUE_ID);
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                1002L,
                "second-member@example.com",
                "두 번째 회원",
                "01000000001");
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(coupon_issue_id, coupon_id, member_id, status, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, 'ISSUED', ?, ?)",
                3002L,
                2001L,
                1002L,
                LocalDateTime.of(2026, 8, 4, 16, 0),
                LocalDateTime.of(2026, 8, 18, 16, 0));

        // when
        List<CouponExpirationCandidate> candidates =
                repository.findDueIssues(LocalDateTime.of(2026, 8, 18, 18, 0), 2);

        // then
        assertThat(candidates)
                .extracting(CouponExpirationCandidate::couponIssueId)
                .containsExactly(3002L, ISSUE_ID);
    }

    @Test
    @DisplayName("사용 요청과 만료 처리의 경쟁에서도 전이 상태와 이력이 하나만 남는다")
    void keepsOneConsistentTransitionWhenUseAndExpirationRace() throws Exception {
        // given
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = CURRENT_TIMESTAMP + INTERVAL 1 MINUTE "
                        + "WHERE coupon_issue_id = ?",
                ISSUE_ID);
        LocalDateTime cutoffAt =
                jdbcTemplate.queryForObject(
                        "SELECT CURRENT_TIMESTAMP + INTERVAL 2 MINUTE",
                        (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> expiration =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return service.expireDueIssues(cutoffAt, 1);
                            });
            Future<Void> use =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                try {
                                    couponUseService.use(ISSUE_ID, "use-expiration-race");
                                } catch (BusinessException ignored) {
                                    // 경쟁에서 진 요청은 전이 실패가 정상이다.
                                }
                                return null;
                            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            expiration.get(10, TimeUnit.SECONDS);
            use.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        // then
        String finalStatus = statusOf(ISSUE_ID);
        assertThat(finalStatus).isIn("USED", "EXPIRED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_issue_history "
                                        + "WHERE coupon_issue_id = ? "
                                        + "AND from_status = 'ISSUED' "
                                        + "AND to_status IN ('USED', 'EXPIRED')",
                                Integer.class,
                                ISSUE_ID))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT to_status FROM coupon_issue_history "
                                        + "WHERE coupon_issue_id = ? "
                                        + "AND to_status IN ('USED', 'EXPIRED')",
                                String.class,
                                ISSUE_ID))
                .isEqualTo(finalStatus);
    }

    private String statusOf(long issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM coupon_issue WHERE coupon_issue_id = ?", String.class, issueId);
    }

    private int expiredHistoryCount(long issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue_history "
                        + "WHERE coupon_issue_id = ? AND to_status = 'EXPIRED'",
                Integer.class,
                issueId);
    }
}
