package com.mocou.admin;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminCouponRepository implements AdminCouponRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAdminCouponRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsCoupon(long couponId) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon WHERE coupon_id = ?", Long.class, couponId);
        return count != null && count > 0;
    }

    @Override
    public Optional<AdminCouponStock> findStock(long couponId) {
        return jdbcTemplate
                .query(
                        "SELECT c.coupon_id, c.name, c.open_at, c.status, cs.total_quantity, "
                                + "cs.remaining_quantity, cs.updated_at "
                                + "FROM coupon c JOIN coupon_stock cs ON cs.coupon_id = c.coupon_id "
                                + "WHERE c.coupon_id = ?",
                        (resultSet, rowNumber) ->
                                new AdminCouponStock(
                                        resultSet.getLong("coupon_id"),
                                        resultSet.getString("name"),
                                        resultSet.getTimestamp("open_at").toLocalDateTime(),
                                        resultSet.getInt("total_quantity"),
                                        resultSet.getInt("total_quantity")
                                                - resultSet.getInt("remaining_quantity"),
                                        resultSet.getInt("remaining_quantity"),
                                        resultSet.getString("status"),
                                        resultSet.getTimestamp("updated_at").toLocalDateTime()),
                        couponId)
                .stream()
                .findFirst();
    }

    @Override
    public long countIssues(long couponId) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?",
                        Long.class,
                        couponId);
        return count == null ? 0 : count;
    }

    @Override
    public List<AdminCouponIssue> findIssues(long couponId, int size, long offset) {
        return jdbcTemplate.query(
                "SELECT ci.coupon_issue_id, ci.coupon_id, ci.member_id, "
                        + "m.name AS member_name, m.email AS member_email, "
                        + "m.phone AS member_phone, ci.status, ci.issued_at, ci.used_at, "
                        + "ci.expires_at FROM coupon_issue ci "
                        + "JOIN member m ON m.member_id = ci.member_id "
                        + "WHERE ci.coupon_id = ? "
                        + "ORDER BY ci.issued_at DESC, ci.coupon_issue_id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) ->
                        AdminCouponIssue.withMaskedMember(
                                resultSet.getLong("coupon_issue_id"),
                                resultSet.getLong("coupon_id"),
                                resultSet.getLong("member_id"),
                                resultSet.getString("member_name"),
                                resultSet.getString("member_email"),
                                resultSet.getString("member_phone"),
                                resultSet.getString("status"),
                                resultSet.getTimestamp("issued_at").toLocalDateTime(),
                                toLocalDateTime(resultSet.getTimestamp("used_at")),
                                resultSet.getTimestamp("expires_at").toLocalDateTime()),
                couponId,
                size,
                offset);
    }

    private static java.time.LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
