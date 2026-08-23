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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 상태 이력이 현재 상태와 일치하고 체인이 끊기지 않았는지 검사한다.
 *
 * <p>다른 규칙들이 한 행 안이나 테이블 간 참조를 보는 반면 이 규칙은 이력의 <b>순서</b>를 본다. 항목마다 묶는 단위가 달라 하나의
 * {@code WHERE}로 합칠 수 없다. 최초 이력 유일성은 발급 건별 개수, 최종 상태 일치는 정렬 후 마지막 한 행, 체인 연결성은 이웃한 두
 * 행의 비교다. 묶기와 정렬은 {@code WHERE}보다 나중에 일어나므로 항목마다 쿼리를 따로 둔다.
 *
 * <p>그래서 위반은 <b>항목 발생 수</b>로 센다. 한 발급 건이 두 항목을 어기면 2건이다. 발급 건 단위로 합치려면 네 항목을
 * {@code UNION}해 중복을 걸러내야 하는데, 그러면 항목별 건수가 리포트에서 사라진다. 네 항목은 조사 방향이 서로 달라 어느 항목이 몇
 * 건인지가 그 자체로 단서다. 관계마다 결과를 더하는 {@link OrphanReferenceRule}과 같은 방식이다.
 */
@Component
class HistoryChainRule implements ConsistencyRule {

    /**
     * 검사 항목 하나.
     *
     * @param checkedSql 이 항목이 몇 개를 대상으로 하는지
     * @param violationCountSql 전체 위반 수. 상세와 같은 기준으로 세야 둘이 어긋나지 않는다
     * @param violationSql 상세. {@code ORDER BY}와 {@code :limit}을 반드시 포함한다
     */
    private record ChainCheck(
            String checkedSql,
            String violationCountSql,
            String violationSql,
            RowMapper<Violation> mapper) {}

    private static final String ISSUE_COUNT_SQL = "SELECT COUNT(*) FROM coupon_issue";
    private static final String HISTORY_COUNT_SQL = "SELECT COUNT(*) FROM coupon_issue_history";

    /**
     * 최초 발급 이력이 정확히 한 건인지 본다.
     *
     * <p>{@code LEFT JOIN}이라 이력이 아예 없는 발급 건도 {@code COUNT}가 0으로 잡혀 검사 범위에 들어온다.
     * {@code INNER JOIN}이면 그런 건이 조인에서 빠져 "이력이 없다"는 위반을 놓친다.
     */
    private static final ChainCheck MISSING_INITIAL_HISTORY =
            new ChainCheck(
                    ISSUE_COUNT_SQL,
                    """
                    SELECT COUNT(*) FROM (
                        SELECT i.coupon_issue_id
                        FROM coupon_issue i
                        LEFT JOIN coupon_issue_history h
                               ON h.coupon_issue_id = i.coupon_issue_id
                              AND h.from_status = 'UNISSUED'
                              AND h.to_status = 'ISSUED'
                        GROUP BY i.coupon_issue_id
                        HAVING COUNT(h.history_id) <> 1
                    ) missing_initial
                    """,
                    """
                    SELECT i.coupon_issue_id, COUNT(h.history_id) AS initial_count
                    FROM coupon_issue i
                    LEFT JOIN coupon_issue_history h
                           ON h.coupon_issue_id = i.coupon_issue_id
                          AND h.from_status = 'UNISSUED'
                          AND h.to_status = 'ISSUED'
                    GROUP BY i.coupon_issue_id
                    HAVING initial_count <> 1
                    ORDER BY i.coupon_issue_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE,
                                    rs.getLong("coupon_issue_id"),
                                    "MISSING_INITIAL_HISTORY: UNISSUED→ISSUED 이력이 %d건"
                                            .formatted(rs.getInt("initial_count"))));

    /**
     * 이력의 끝과 현재 상태가 같은지 본다.
     *
     * <p>정렬 기준에 {@code history_id}를 함께 넣는다. {@code changed_at}이 같은 이력이 있으면 어느 쪽이 마지막인지
     * 실행마다 달라지고, 그러면 같은 데이터로 재실행해도 판정이 흔들려 재현성이 깨진다.
     */
    private static final ChainCheck FINAL_STATUS_MISMATCH =
            new ChainCheck(
                    ISSUE_COUNT_SQL,
                    """
                    WITH last_history AS (
                        SELECT coupon_issue_id, to_status,
                               ROW_NUMBER() OVER (
                                   PARTITION BY coupon_issue_id
                                   ORDER BY changed_at DESC, history_id DESC
                               ) AS rn
                        FROM coupon_issue_history
                    )
                    SELECT COUNT(*)
                    FROM coupon_issue i
                    JOIN last_history l ON l.coupon_issue_id = i.coupon_issue_id AND l.rn = 1
                    WHERE i.status <> l.to_status
                    """,
                    """
                    WITH last_history AS (
                        SELECT coupon_issue_id, to_status,
                               ROW_NUMBER() OVER (
                                   PARTITION BY coupon_issue_id
                                   ORDER BY changed_at DESC, history_id DESC
                               ) AS rn
                        FROM coupon_issue_history
                    )
                    SELECT i.coupon_issue_id, i.status, l.to_status AS last_to_status
                    FROM coupon_issue i
                    JOIN last_history l ON l.coupon_issue_id = i.coupon_issue_id AND l.rn = 1
                    WHERE i.status <> l.to_status
                    ORDER BY i.coupon_issue_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE,
                                    rs.getLong("coupon_issue_id"),
                                    "FINAL_STATUS_MISMATCH: 상태는 %s인데 마지막 이력은 %s"
                                            .formatted(
                                                    rs.getString("status"), rs.getString("last_to_status"))));

    /**
     * 이웃한 두 이력이 이어지는지 본다. 앞 이력의 도착 상태가 다음 이력의 출발 상태여야 한다.
     *
     * <p>{@code LAG}가 정렬된 결과를 훑으며 직전 행의 값을 가져온다. 서브쿼리로 매번 앞 행을 다시 찾으면 이력 600만 행마다 탐색이
     * 일어난다. {@code PARTITION BY}가 발급 건마다 칸막이를 쳐서 다른 발급 건의 이력과 이어지지 않게 한다.
     *
     * <p>대상이 발급 건이 아니라 이력 행이다. 어느 줄에서 끊겼는지가 조사의 출발점이다.
     */
    private static final ChainCheck BROKEN_CHAIN =
            new ChainCheck(
                    HISTORY_COUNT_SQL,
                    """
                    WITH chain AS (
                        SELECT from_status,
                               LAG(to_status) OVER (
                                   PARTITION BY coupon_issue_id
                                   ORDER BY changed_at, history_id
                               ) AS prev_to_status
                        FROM coupon_issue_history
                    )
                    SELECT COUNT(*) FROM chain
                    WHERE prev_to_status IS NOT NULL AND prev_to_status <> from_status
                    """,
                    """
                    WITH chain AS (
                        SELECT history_id, from_status,
                               LAG(to_status) OVER (
                                   PARTITION BY coupon_issue_id
                                   ORDER BY changed_at, history_id
                               ) AS prev_to_status
                        FROM coupon_issue_history
                    )
                    SELECT history_id, prev_to_status, from_status FROM chain
                    WHERE prev_to_status IS NOT NULL AND prev_to_status <> from_status
                    ORDER BY history_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE_HISTORY,
                                    rs.getLong("history_id"),
                                    "BROKEN_CHAIN: 직전 이력은 %s로 끝났는데 %s에서 시작"
                                            .formatted(
                                                    rs.getString("prev_to_status"), rs.getString("from_status"))));

    /** 이력이 발급보다 먼저 기록됐는지 본다. 이 항목만 한 행 안에서 판정되지만, 묶어둘 곳이 없어 함께 둔다. */
    private static final ChainCheck HISTORY_BEFORE_ISSUE =
            new ChainCheck(
                    HISTORY_COUNT_SQL,
                    """
                    SELECT COUNT(*)
                    FROM coupon_issue_history h
                    JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
                    WHERE h.changed_at < i.issued_at
                    """,
                    """
                    SELECT h.history_id, h.changed_at, i.issued_at
                    FROM coupon_issue_history h
                    JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
                    WHERE h.changed_at < i.issued_at
                    ORDER BY h.history_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE_HISTORY,
                                    rs.getLong("history_id"),
                                    "HISTORY_BEFORE_ISSUE: 이력 %s, 발급 %s"
                                            .formatted(
                                                    rs.getTimestamp("changed_at").toLocalDateTime(),
                                                    rs.getTimestamp("issued_at").toLocalDateTime())));

    private static final List<ChainCheck> CHECKS =
            List.of(
                    MISSING_INITIAL_HISTORY, FINAL_STATUS_MISMATCH, BROKEN_CHAIN, HISTORY_BEFORE_ISSUE);

    @Override
    public VerificationRule rule() {
        return VerificationRule.HISTORY_MISMATCH;
    }

    @Override
    public RuleOutcome check(NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        long checkedCount = 0;
        long violationCount = 0;
        List<Violation> violations = new ArrayList<>();

        for (ChainCheck check : CHECKS) {
            checkedCount += RuleQueries.count(jdbcTemplate, check.checkedSql());
            long found = RuleQueries.count(jdbcTemplate, check.violationCountSql());
            violationCount += found;

            int remaining = context.violationLimit() - violations.size();
            if (found > 0 && remaining > 0) {
                violations.addAll(
                        jdbcTemplate.query(
                                check.violationSql(), Map.of("limit", remaining), check.mapper()));
            }
        }

        if (violationCount == 0) {
            return RuleOutcome.passed(rule(), checkedCount);
        }
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }
}
