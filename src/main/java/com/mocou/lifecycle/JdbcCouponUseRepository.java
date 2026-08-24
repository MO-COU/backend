package com.mocou.lifecycle;

import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCouponUseRepository implements CouponUseRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCouponUseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CouponIssueStatus> findHistoryTargetStatus(
            long issueId, String idempotencyKey) {
        return jdbcTemplate
                .query(
                        "SELECT to_status FROM coupon_issue_history "
                                + "WHERE coupon_issue_id = ? "
                                + "AND BINARY idempotency_key = BINARY ?",
                        (resultSet, rowNumber) ->
                                CouponIssueStatus.valueOf(resultSet.getString("to_status")),
                        issueId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public int markUsed(long issueId) {
        return jdbcTemplate.update(
                "UPDATE coupon_issue "
                        + "SET status = 'USED', used_at = CURRENT_TIMESTAMP "
                        + "WHERE coupon_issue_id = ? "
                        + "AND status = 'ISSUED' "
                        + "AND expires_at > CURRENT_TIMESTAMP",
                issueId);
    }

    @Override
    public void saveUsedHistory(long issueId, String idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history "
                        + "(coupon_issue_id, from_status, to_status, changed_at, idempotency_key) "
                        + "VALUES (?, 'ISSUED', 'USED', CURRENT_TIMESTAMP, ?)",
                issueId,
                idempotencyKey);
    }

    @Override
    public Optional<CouponIssueState> findIssue(long issueId) {
        return jdbcTemplate
                .query(
                        "SELECT coupon_issue_id, coupon_id, member_id, status, used_at, "
                                + "expires_at <= CURRENT_TIMESTAMP AS expired "
                                + "FROM coupon_issue WHERE coupon_issue_id = ?",
                        (resultSet, rowNumber) -> {
                            Timestamp usedAt = resultSet.getTimestamp("used_at");
                            return new CouponIssueState(
                                    resultSet.getLong("coupon_issue_id"),
                                    resultSet.getLong("coupon_id"),
                                    resultSet.getLong("member_id"),
                                    CouponIssueStatus.valueOf(resultSet.getString("status")),
                                    usedAt == null ? null : usedAt.toLocalDateTime(),
                                    resultSet.getBoolean("expired"));
                        },
                        issueId)
                .stream()
                .findFirst();
    }
}
