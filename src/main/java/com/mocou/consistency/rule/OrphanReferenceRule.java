package com.mocou.consistency.rule;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.Violation;
import com.mocou.consistency.ViolationTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 존재하지 않는 회원·쿠폰·발급 건을 가리키는 행이 있는지 검사한다.
 *
 * <p>네 참조 관계 모두 FK가 걸려 있어 앱이 정상 동작하는 한 고아가 생길 수 없다. 그럼에도 검사하는 이유는 제약을 끄는 경로가 실제로
 * 쓰이기 때문이다. 데이터 초기화 절차가 {@code SET FOREIGN_KEY_CHECKS = 0}을 쓰고, 부분 삭제나 {@code mysqldump}
 * 복원도 같은 방식이다. 검사를 끈 상태에서는 자식이 남아 있어도 부모가 지워지며 에러도 나지 않는다.
 *
 * <p>{@code issue_failure_log}는 검사 대상이 아니다. {@code COUPON_NOT_FOUND} 실패를 기록하려면 존재하지 않는
 * ID도 넣을 수 있어야 해서 의도적으로 FK를 걸지 않은 테이블이다. 포함하면 정상 데이터가 전부 위반으로 잡힌다.
 */
@Component
class OrphanReferenceRule implements ConsistencyRule {

    /**
     * 검사할 참조 관계 하나.
     *
     * @param missingTarget 짝을 찾지 못한 값이 원래 있어야 할 곳. 위반의 대상이 된다
     */
    private record Reference(
            ViolationTarget missingTarget,
            String childTable,
            String childColumn,
            String parentTable,
            String parentKey) {

        String childLabel() {
            return childTable + "." + childColumn;
        }
    }

    /** 모든 자식 컬럼이 {@code NOT NULL}이라 {@code NULL} 값이 끊긴 참조로 잡힐 여지는 없다. */
    private static final List<Reference> REFERENCES =
            List.of(
                    new Reference(
                            ViolationTarget.COUPON, "coupon_issue", "coupon_id", "coupon", "coupon_id"),
                    new Reference(
                            ViolationTarget.MEMBER, "coupon_issue", "member_id", "member", "member_id"),
                    new Reference(
                            ViolationTarget.COUPON_ISSUE,
                            "coupon_issue_history",
                            "coupon_issue_id",
                            "coupon_issue",
                            "coupon_issue_id"),
                    new Reference(
                            ViolationTarget.COUPON, "coupon_stock", "coupon_id", "coupon", "coupon_id"));

    @Override
    public VerificationRule rule() {
        return VerificationRule.ORPHAN_REFERENCE;
    }

    /**
     * 관계마다 검사하고 결과를 합친다.
     *
     * <p>위반은 행이 아니라 <b>짝을 찾지 못한 값</b> 단위로 센다. 고아는 부모 하나가 사라져 자식 수천 개가 한꺼번에 끊기는 형태로
     * 나타나므로, 행마다 기록하면 같은 내용이 상한까지 반복되어 정작 무엇이 사라졌는지가 묻힌다. 값으로 묶으면 위반 한 줄에 영향받은 행 수까지
     * 담긴다. 끊긴 값을 알면 실제 행은 자식 테이블에서 바로 찾을 수 있어 잃는 정보도 없다.
     */
    @Override
    public RuleOutcome check(NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        long checkedCount = 0;
        long violationCount = 0;
        List<Violation> violations = new ArrayList<>();

        for (Reference reference : REFERENCES) {
            checkedCount += RuleQueries.count(jdbcTemplate, childCountSql(reference));
            long orphanedValues = RuleQueries.count(jdbcTemplate, violationCountSql(reference));
            violationCount += orphanedValues;

            int remaining = context.violationLimit() - violations.size();
            if (orphanedValues > 0 && remaining > 0) {
                violations.addAll(violations(jdbcTemplate, reference, remaining));
            }
        }

        if (violationCount == 0) {
            return RuleOutcome.passed(rule(), checkedCount);
        }
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }

    private List<Violation> violations(
            NamedParameterJdbcTemplate jdbcTemplate, Reference reference, int limit) {
        return jdbcTemplate.query(
                violationSql(reference),
                Map.of("limit", limit),
                (rs, rowNum) ->
                        Violation.of(
                                reference.missingTarget(),
                                rs.getLong("missing_value"),
                                "%s %d건이 참조하는데 %s에 없음"
                                        .formatted(
                                                reference.childLabel(),
                                                rs.getLong("affected_rows"),
                                                reference.parentTable())));
    }

    private String childCountSql(Reference reference) {
        return "SELECT COUNT(*) FROM " + reference.childTable();
    }

    /**
     * 끊긴 값이 몇 종류인지 센다. 상세와 같은 기준으로 묶어야 집계와 목록이 어긋나지 않는다.
     *
     * <p>SQL을 문자열로 조립하지만 테이블·컬럼 이름은 전부 이 클래스 안의 상수다. 외부 입력이 들어가는 자리는 {@code LIMIT}
     * 하나뿐이며 그것만 바인딩한다.
     */
    private String violationCountSql(Reference reference) {
        return """
               SELECT COUNT(*) FROM (
                   SELECT c.%1$s
                   FROM %2$s c
                   LEFT JOIN %3$s p ON p.%4$s = c.%1$s
                   WHERE p.%4$s IS NULL
                   GROUP BY c.%1$s
               ) orphaned
               """
                .formatted(
                        reference.childColumn(),
                        reference.childTable(),
                        reference.parentTable(),
                        reference.parentKey());
    }

    /**
     * 자식 300만 행을 그대로 조인하지 않고 값으로 묶어 확인한다. 발급 300만 건이 가리키는 쿠폰은 301종뿐이라 조인 규모가 크게 줄고,
     * 결과도 사람이 읽기 좋은 단위가 된다.
     */
    private String violationSql(Reference reference) {
        return """
               SELECT c.%1$s AS missing_value, COUNT(*) AS affected_rows
               FROM %2$s c
               LEFT JOIN %3$s p ON p.%4$s = c.%1$s
               WHERE p.%4$s IS NULL
               GROUP BY c.%1$s
               ORDER BY c.%1$s
               LIMIT :limit
               """
                .formatted(
                        reference.childColumn(),
                        reference.childTable(),
                        reference.parentTable(),
                        reference.parentKey());
    }
}
