package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponExpirationIntegrationTest extends MySqlContainerTest {

    private static final long ISSUE_ID = 3001L;

    @Autowired private CouponExpirationService service;

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_expired_history");
    }

    @Test
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
