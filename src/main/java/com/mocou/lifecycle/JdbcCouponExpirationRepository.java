package com.mocou.lifecycle;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCouponExpirationRepository implements CouponExpirationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCouponExpirationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LocalDateTime currentDatabaseTime() {
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
    }

    @Override
    public List<CouponExpirationCandidate> findDueIssues(LocalDateTime cutoffAt, int limit) {
        return jdbcTemplate.query(
                "SELECT coupon_issue_id, expires_at FROM coupon_issue "
                        + "WHERE status = 'ISSUED' AND expires_at <= ? "
                        + "ORDER BY coupon_issue_id LIMIT ?",
                (resultSet, rowNumber) ->
                        new CouponExpirationCandidate(
                                resultSet.getLong("coupon_issue_id"),
                                resultSet.getTimestamp("expires_at").toLocalDateTime()),
                cutoffAt,
                limit);
    }

    @Override
    public int markExpired(long issueId, LocalDateTime cutoffAt) {
        return jdbcTemplate.update(
                "UPDATE coupon_issue SET status = 'EXPIRED' "
                        + "WHERE coupon_issue_id = ? AND status = 'ISSUED' AND expires_at <= ?",
                issueId,
                cutoffAt);
    }

    @Override
    public void saveExpiredHistory(CouponExpirationCandidate candidate) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history "
                        + "(coupon_issue_id, from_status, to_status, changed_at, idempotency_key) "
                        + "VALUES (?, 'ISSUED', 'EXPIRED', CURRENT_TIMESTAMP, ?)",
                candidate.couponIssueId(),
                "EXPIRE:" + candidate.couponIssueId() + ":" + candidate.expiresAt());
    }
}
