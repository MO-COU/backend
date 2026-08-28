package com.mocou.consistency.rule;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.Violation;
import com.mocou.consistency.ViolationTarget;
import java.sql.Timestamp;
import java.util.Map;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 발급 한 행 안에서 상태와 시각이 서로 어긋나는지 검사한다.
 *
 * <p>앞선 규칙들이 테이블 사이의 관계를 보는 반면 이 규칙은 한 행 안만 본다. 검사 항목이 8개인데 항목마다 쿼리를 던지면 300만 행을 여덟
 * 번 훑게 되므로, 한 번 훑으면서 전부 판정한다.
 *
 * <p>한 행이 여러 항목을 동시에 어길 수 있다. 위반 수는 <b>어긋난 행의 수</b>로 세고 어긴 항목은 상세에 나열한다. 항목 수로 세면 한
 * 행이 여러 건으로 잡혀 집계와 상세 목록이 어긋난다.
 */
@Component
class StateTimestampRule implements ConsistencyRule {

    private static final String CHECKED_SQL = "SELECT COUNT(*) FROM coupon_issue";

    /**
     * 8개 항목을 {@code OR}로 이은 조건. 집계와 상세가 같은 기준을 봐야 하므로 두 쿼리가 이 절을 공유한다.
     *
     * <p>{@code EXPIRY_OVERDUE}에 유예를 두는 이유는 만료 전환이 배치의 일괄 처리이기 때문이다. 만료 시각 도달과 상태 전환
     * 사이에는 필연적으로 지연이 있어, 유예 없이 자르면 검증이 "배치가 방금 돈 직후"에만 통과한다. 유예 값은 배치 주기에서 파생되며
     * {@link VerificationContext}로 전달받는다.
     */
    private static final String CONDITION =
            """
            WHERE (i.status = 'USED' AND i.used_at IS NULL)
               OR (i.status <> 'USED' AND i.used_at IS NOT NULL)
               OR i.used_at < i.issued_at
               OR i.expires_at <= i.issued_at
               OR i.issued_at > :snapshotAt
               OR (i.status = 'ISSUED' AND i.expires_at <= :snapshotAt - INTERVAL :graceSeconds SECOND)
               OR (i.status = 'EXPIRED' AND i.expires_at > :snapshotAt)
               OR m.created_at > i.issued_at
            """;

    private static final String FROM =
            """
            FROM coupon_issue i
            JOIN member m ON m.member_id = i.member_id
            """;

    /**
     * 조인을 hash join으로 유도하는 힌트.
     *
     * <p>조건 8개 중 {@code ISSUED_BEFORE_SIGNUP} 하나가 member를 필요로 해 발급 300만 행 전체가 member와
     * 조인되는데, 옵티마이저는 이 조인을 Nested loop으로 푼다 — member 100만을 훑으며 한 명당 발급 몇 건을
     * {@code idx_issue_member}로 랜덤 접근한다(세컨더리 인덱스에서 본체로 300만 회 왕복, 실측 25초).
     * 조인용 인덱스를 막으면 양쪽을 한 번씩 순차 스캔해 해시로 맞추고, 같은 검사가 2.4초에 끝난다.
     *
     * <p>옵티마이저가 hash join을 스스로 고르지 못하는 이유는 비용 모델이 그것을 9만 배 비싸다고
     * 추정하기 때문이다(추정 296e+9 vs 3.25e+6 — 실측은 반대로 10배 빠르다). 랜덤 접근의 실비용이
     * 모델에 덜 반영돼 있다.
     *
     * <p>데이터가 늘어도 이 선택은 뒤집히지 않는다. {@code OR} 조건이 두 테이블에 걸쳐 있어 인덱스로 행을
     * 미리 거를 수 없고, 양쪽 풀스캔이 구조적으로 불가피하다. 풀스캔이 전제라면 hash join이 항상 낫다.
     *
     * <p>{@code IGNORE INDEX}가 아니라 옵티마이저 힌트를 쓴다. 전자는 인덱스 이름이 바뀌면 쿼리가 오류로
     * 죽지만, 힌트는 경고만 내고 무시되어 느려질 뿐 죽지 않는다. 검증 도구는 죽지 않는 쪽이 우선이다.
     */
    private static final String HASH_JOIN_HINT =
            "/*+ NO_INDEX(i idx_issue_member) NO_INDEX(m PRIMARY) */ ";

    private static final String VIOLATION_COUNT_SQL =
            "SELECT " + HASH_JOIN_HINT + "COUNT(*) " + FROM + CONDITION;

    /**
     * {@code CONCAT_WS}는 {@code NULL} 인자를 건너뛴다. 조건에 걸리지 않은 항목은 {@code CASE}가
     * {@code NULL}을 내므로 목록에서 자동으로 빠진다.
     *
     * <p>{@code SELECT} 별칭에 {@code HAVING}을 걸어 거르지 않는 이유는, MySQL에서 {@code GROUP BY} 없는
     * {@code HAVING}이 전체를 한 그룹으로 묶어 최대 1행만 반환하기 때문이다. 조건을 {@code WHERE}에 한 번 더 쓰는 편이
     * 장황해 보여도 300만 행을 임시 테이블로 만들지 않고 스캔하면서 걸러낸다.
     */
    private static final String VIOLATION_SQL =
            "SELECT "
                    + HASH_JOIN_HINT
                    + """
                    i.coupon_issue_id,
                   CONCAT_WS(',',
                     CASE WHEN i.status = 'USED' AND i.used_at IS NULL
                          THEN 'USED_WITHOUT_TIMESTAMP' END,
                     CASE WHEN i.status <> 'USED' AND i.used_at IS NOT NULL
                          THEN 'UNUSED_WITH_TIMESTAMP' END,
                     CASE WHEN i.used_at < i.issued_at
                          THEN 'USED_BEFORE_ISSUED' END,
                     CASE WHEN i.expires_at <= i.issued_at
                          THEN 'INVALID_VALIDITY' END,
                     CASE WHEN i.issued_at > :snapshotAt
                          THEN 'FUTURE_ISSUE' END,
                     CASE WHEN i.status = 'ISSUED' AND i.expires_at <= :snapshotAt - INTERVAL :graceSeconds SECOND
                          THEN 'EXPIRY_OVERDUE' END,
                     CASE WHEN i.status = 'EXPIRED' AND i.expires_at > :snapshotAt
                          THEN 'PREMATURE_EXPIRY' END,
                     CASE WHEN m.created_at > i.issued_at
                          THEN 'ISSUED_BEFORE_SIGNUP' END
                   ) AS reasons
            """
                    + FROM
                    + CONDITION
                    + """
                    ORDER BY i.coupon_issue_id
                    LIMIT :limit
                    """;

    @Override
    public VerificationRule rule() {
        return VerificationRule.STATE_TIMESTAMP_MISMATCH;
    }

    @Override
    public RuleOutcome check(
            NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        long checkedCount = RuleQueries.count(jdbcTemplate, CHECKED_SQL);
        Map<String, Object> params = params(context);

        long violationCount = RuleQueries.count(jdbcTemplate, VIOLATION_COUNT_SQL, params);
        if (violationCount == 0) {
            return RuleOutcome.passed(rule(), checkedCount);
        }

        List<Violation> violations =
                jdbcTemplate.query(
                        VIOLATION_SQL,
                        params,
                        (rs, rowNum) ->
                                Violation.of(
                                        ViolationTarget.COUPON_ISSUE,
                                        rs.getLong("coupon_issue_id"),
                                        rs.getString("reasons")));
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }

    /**
     * 판정에 쓰는 값 세 개.
     *
     * <p>{@code :snapshotAt}은 {@code CASE}와 {@code WHERE}에 각각 세 번씩, 모두 여섯 번 등장한다. 위치
     * 기반 {@code ?}였다면 같은 값을 여섯 번 넘겨야 하고 순서가 어긋나도 예외가 나지 않는다. 이름을 쓰면 한 번만 넘긴다.
     *
     * <p>집계 쿼리는 {@code :limit}을 쓰지 않지만 함께 넘겨도 무해하다. 이름 방식은 SQL에 없는 값을 무시한다.
     */
    private Map<String, Object> params(VerificationContext context) {
        return Map.of(
                "snapshotAt", Timestamp.valueOf(context.snapshotAt()),
                "graceSeconds", context.graceSeconds(),
                "limit", context.violationLimit());
    }
}
