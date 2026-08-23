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
 * 한 회원이 같은 쿠폰을 2장 이상 받았는지 검사한다(`FR-2.3`).
 *
 * <p>현재 구조에서는 위반이 나올 수 없다. {@code uk_issue_coupon_member}가 INSERT 단계에서 막기 때문이다. 그럼에도
 * 규칙으로 두는 이유는 제약이 살아 있는지를 검증이 매번 확인해 주고, Redis 발급 결과를 DB로 비동기 동기화하는 경로가 붙으면 검출 대상이
 * 생기기 때문이다.
 */
@Component
class DuplicateIssueRule implements ConsistencyRule {

    private static final String CHECKED_SQL = "SELECT COUNT(*) FROM coupon_issue";

    /**
     * 위반 수는 "중복이 발생한 조합의 개수"다. 한 조합에서 3장이 발급됐어도 조합 하나로 센다. 상세 행이 조합 단위로 남으므로 집계도 같은
     * 단위여야 둘이 어긋나지 않는다.
     */
    private static final String VIOLATION_COUNT_SQL =
            """
            SELECT COUNT(*) FROM (
                SELECT coupon_id FROM coupon_issue
                GROUP BY coupon_id, member_id HAVING COUNT(*) > 1
            ) duplicated
            """;

    /**
     * {@code GROUP BY (coupon_id, member_id)}는 {@code uk_issue_coupon_member}의 컬럼 순서를 그대로 따라가므로
     * 인덱스만으로 집계된다. 정렬 기준도 같은 인덱스를 쓴다.
     */
    private static final String VIOLATION_SQL =
            """
            SELECT coupon_id, member_id, COUNT(*) AS issue_count
            FROM coupon_issue
            GROUP BY coupon_id, member_id
            HAVING COUNT(*) > 1
            ORDER BY coupon_id, member_id
            LIMIT ?
            """;

    @Override
    public VerificationRule rule() {
        return VerificationRule.DUPLICATE_ISSUE;
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
                                new Violation(
                                        ViolationTarget.COUPON_MEMBER_PAIR,
                                        rs.getLong("coupon_id"),
                                        rs.getLong("member_id"),
                                        "발급 %d건".formatted(rs.getLong("issue_count"))),
                        context.violationLimit());
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }
}
