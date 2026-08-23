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
 * {@code 총재고 = 발급 건수 + 잔여 재고}가 성립하는지 검사한다(`FR-3.3`, `NFR-2`).
 *
 * <p>더미데이터를 적재한 직후에는 위반이 나올 수 없다. 잔여 재고를 발급 건수로 역산해 채우므로 정의상 성립한다. 이 규칙이 의미를 갖는 것은
 * 발급 경로가 붙은 뒤다. 발급하면서 {@code coupon_stock.remaining_quantity}를 갱신하지 않으면 부하 테스트 직후 검출된다.
 */
@Component
class StockMismatchRule implements ConsistencyRule {

    private static final String CHECKED_SQL = "SELECT COUNT(*) FROM coupon_stock";

    /**
     * 여기서 {@code LEFT JOIN}은 선택이 아니다. 시연 회차는 발급이 0건이고 잔여가 총재고와 같아 항등식이 성립하는데,
     * {@code INNER JOIN}으로 쓰면 그 쿠폰이 아예 검사되지 않는다. 검사하지 않은 것과 통과한 것이 결과에서 구분되지 않는다.
     */
    private static final String VIOLATION_COUNT_SQL =
            """
            SELECT COUNT(*) FROM (
                SELECT s.coupon_id
                FROM coupon_stock s
                LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
                GROUP BY s.coupon_id, s.total_quantity, s.remaining_quantity
                HAVING s.total_quantity <> s.remaining_quantity + COUNT(i.coupon_issue_id)
            ) mismatched
            """;

    private static final String VIOLATION_SQL =
            """
            SELECT s.coupon_id, s.total_quantity, s.remaining_quantity,
                   COUNT(i.coupon_issue_id) AS issued
            FROM coupon_stock s
            LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
            GROUP BY s.coupon_id, s.total_quantity, s.remaining_quantity
            HAVING s.total_quantity <> s.remaining_quantity + issued
            ORDER BY s.coupon_id
            LIMIT ?
            """;

    @Override
    public VerificationRule rule() {
        return VerificationRule.STOCK_MISMATCH;
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
                                        "총재고 %d, 발급 %d, 잔여 %d"
                                                .formatted(
                                                        rs.getLong("total_quantity"),
                                                        rs.getLong("issued"),
                                                        rs.getLong("remaining_quantity"))),
                        context.violationLimit());
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }
}
