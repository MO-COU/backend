package com.mocou.consistency.rule;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.Violation;
import com.mocou.consistency.ViolationTarget;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 쿠폰별 발급 건수가 총 재고를 넘었는지 검사한다(`FR-2.1`, `NFR-1`).
 *
 * <p>초과 발급을 막는 DB 제약은 없다. 재고 차감이 애플리케이션과 Redis의 책임이라 이 규칙은 실제로 위반을 검출할 수 있다.
 */
@Component
class OverIssueRule implements ConsistencyRule {

    private static final String CHECKED_SQL = "SELECT COUNT(*) FROM coupon_stock";

    /**
     * {@code LEFT JOIN}이어야 한다. {@code INNER JOIN}으로 바꾸면 발급 이력이 없는 시연 회차가 검사 범위에서 조용히
     * 빠진다.
     */
    private static final String VIOLATION_COUNT_SQL =
            """
            SELECT COUNT(*) FROM (
                SELECT s.coupon_id
                FROM coupon_stock s
                LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
                GROUP BY s.coupon_id, s.total_quantity
                HAVING COUNT(i.coupon_issue_id) > s.total_quantity
            ) over_issued
            """;

    private static final String VIOLATION_SQL =
            """
            SELECT s.coupon_id, s.total_quantity, COUNT(i.coupon_issue_id) AS issued
            FROM coupon_stock s
            LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
            GROUP BY s.coupon_id, s.total_quantity
            HAVING issued > s.total_quantity
            ORDER BY s.coupon_id
            LIMIT ?
            """;

    @Override
    public VerificationRule rule() {
        return VerificationRule.OVER_ISSUE;
    }

    @Override
    public RuleOutcome check(JdbcTemplate jdbcTemplate, VerificationContext context) {
        long checkedCount = RuleQueries.count(jdbcTemplate, CHECKED_SQL);
        long violationCount = RuleQueries.count(jdbcTemplate, VIOLATION_COUNT_SQL);
        if (violationCount == 0) {
            return RuleOutcome.passed(rule(), checkedCount);
        }

        List<Violation> violations =
                jdbcTemplate.query(
                        VIOLATION_SQL,
                        (rs, rowNum) ->
                                Violation.of(
                                        ViolationTarget.COUPON,
                                        rs.getLong("coupon_id"),
                                        "총재고 %d, 발급 %d"
                                                .formatted(rs.getLong("total_quantity"), rs.getLong("issued"))),
                        context.violationLimit());
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }
}
