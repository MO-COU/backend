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
 * Redis가 확정한 예약 순번과 잔여 재고가 DB까지 온전히 왔는지 검사한다.
 *
 * <p><b>왜 DB에서 검사하나.</b> 순번을 정하는 권위는 Redis Lua의 원자적 실행뿐인데, 그 결과를 Redis 안에서 다시 대조해봐야
 * 같은 실행이 같은 순간에 쓴 값끼리 맞춰보는 것이라 어긋날 방법이 없다. 검증이 아니라 동어반복이다. 게다가 Redis 키는 리셋하면
 * 사라져 지나간 회차를 볼 수 없고, 검증에 필요한 시점 고정({@code CONSISTENT SNAPSHOT})에 해당하는 수단도 없다.
 *
 * <p>DB로 옮겨 적으면 <b>외부 기준</b>이 생긴다. {@code total_quantity}는 Redis 출신이 아니라 DB가 원래 갖고 있던
 * 값이고, 구멍·중복 검사도 Redis 출신 순번을 DB의 행 수와 대조한다.
 *
 * <p><b>무엇을 증명하지 않나.</b> "선착순이 공정했다"는 Lua 싱글 스레드 원자 실행이라는 구조가 보장하는 것이지 데이터를 사후에
 * 뜯어서 증명할 성질이 아니다. 이 규칙이 증명하는 것은 Redis가 정한 결과가 DB까지 온전히 왔는가뿐이다.
 *
 * <p>위반은 {@link HistoryChainRule}과 같이 <b>항목 발생 수</b>로 센다. 행 단위 둘과 쿠폰 단위 둘이라 세는 단위가 달라
 * 하나로 합칠 수 없다.
 */
@Component
class IssueSequenceRule implements ConsistencyRule {

    /**
     * 검사 항목 하나.
     *
     * @param checkedSql 이 항목이 몇 개를 대상으로 하는지
     * @param violationCountSql 전체 위반 수. 상세와 같은 기준으로 세야 둘이 어긋나지 않는다
     * @param violationSql 상세. {@code ORDER BY}와 {@code :limit}을 반드시 포함한다
     */
    private record SequenceCheck(
            String checkedSql,
            String violationCountSql,
            String violationSql,
            RowMapper<Violation> mapper) {}

    /**
     * 부하 테스트를 거친 발급 건만 검사 대상이다.
     *
     * <p>더미데이터 300만 건은 {@code IssueGenerator}가 Redis를 거치지 않고 직접 적재하므로 두 컬럼이 {@code NULL}이다.
     * 여기서 {@code NULL}은 결측이 아니라 "검사 대상 아님"을 뜻한다.
     */
    private static final String SEQUENCED_COUNT_SQL =
            "SELECT COUNT(*) FROM coupon_issue WHERE issue_sequence IS NOT NULL";

    /** 한쪽만 적힌 행을 찾으려면 두 컬럼 중 하나라도 있는 행을 다 봐야 한다. */
    private static final String TOUCHED_COUNT_SQL =
            """
            SELECT COUNT(*) FROM coupon_issue
            WHERE issue_sequence IS NOT NULL OR remaining_at_issue IS NOT NULL
            """;

    private static final String SEQUENCED_COUPON_COUNT_SQL =
            """
            SELECT COUNT(*) FROM (
                SELECT coupon_id FROM coupon_issue
                WHERE issue_sequence IS NOT NULL
                GROUP BY coupon_id
            ) sequenced_coupons
            """;

    /**
     * 두 카운터가 따로 놀았는지 본다.
     *
     * <p>Lua는 예약 성공 순간 재고를 {@code DECR}하고 순번을 {@code INCR}한다. 서로 다른 키를 건드리는 별개의 카운터라
     * k번째 예약은 순번 k, 잔여 (총재고 − k)를 받는다. 그래서 보상이 없는 동안은 다음이 성립한다.
     *
     * <pre>issue_sequence + remaining_at_issue = total_quantity</pre>
     *
     * <p><b>그런데 등식으로 검사하면 안 된다.</b> 보상({@code compensate-coupon.lua})은 재고를 {@code INCR}로
     * 되살리면서 순번 카운터는 되돌리지 않는다. 되돌리면 이미 더 큰 순번을 받아간 회원과 중복되기 때문이고, 그게 옳다. 대신 보상
     * 이후의 예약부터는 합이 커진다.
     *
     * <pre>issue_sequence + remaining_at_issue = total_quantity + (그때까지의 누적 보상 수)</pre>
     *
     * <p>누적 보상 수는 늘기만 하므로 항상 성립하는 것은 <b>부등식과 단조성</b> 둘이다.
     *
     * <pre>
     * (1) 합 &gt;= total_quantity
     * (2) 순번 순으로 정렬했을 때 합이 감소하지 않는다
     * </pre>
     *
     * <p>(2)가 깨지면 보상으로는 설명할 수 없다. 등식으로 검사하면 보상 1건 뒤의 <b>정상 예약이 전부 위반으로 잡혀</b> 리포트가
     * 엉뚱한 행을 지목한다. 보상이 실제로 났다는 사실은 {@link #SEQUENCE_GAP}이 구멍으로 드러내므로 역할이 겹치지도 않는다.
     *
     * <p>{@code LAG}는 {@link HistoryChainRule}에서 걷어낸 윈도우 함수지만 여기서는 다르다. 거기는 이력 599만 행
     * 전체를 버퍼에 쌓았고, 여기는 {@code issue_sequence IS NOT NULL}이 더미 300만 건을 걷어내 부하 테스트분만 남는다.
     * 같은 도구라도 보는 데이터가 다르면 답이 달라진다.
     */
    private static final String DIVERGED_CONDITION =
            """
            WITH sequenced AS (
                SELECT i.coupon_issue_id,
                       i.coupon_id,
                       i.issue_sequence,
                       i.issue_sequence + i.remaining_at_issue AS total_at_issue,
                       s.total_quantity,
                       LAG(i.issue_sequence + i.remaining_at_issue) OVER (
                           PARTITION BY i.coupon_id
                           ORDER BY i.issue_sequence
                       ) AS prev_total_at_issue
                FROM coupon_issue i
                JOIN coupon_stock s ON s.coupon_id = i.coupon_id
                WHERE i.issue_sequence IS NOT NULL
                  AND i.remaining_at_issue IS NOT NULL
            )
            """;

    private static final String DIVERGED_FILTER =
            """
            WHERE total_at_issue < total_quantity
               OR (prev_total_at_issue IS NOT NULL AND total_at_issue < prev_total_at_issue)
            """;

    private static final SequenceCheck SEQUENCE_STOCK_DIVERGED =
            new SequenceCheck(
                    SEQUENCED_COUNT_SQL,
                    DIVERGED_CONDITION
                            + "SELECT COUNT(*) FROM sequenced "
                            + DIVERGED_FILTER,
                    DIVERGED_CONDITION
                            + """
                            SELECT coupon_issue_id, issue_sequence, total_at_issue, total_quantity
                            FROM sequenced
                            """
                            + DIVERGED_FILTER
                            + """
                            ORDER BY coupon_issue_id
                            LIMIT :limit
                            """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE,
                                    rs.getLong("coupon_issue_id"),
                                    "SEQUENCE_STOCK_DIVERGED: 순번 %d일 때 순번+잔여가 %d인데 총재고는 %d"
                                            .formatted(
                                                    rs.getLong("issue_sequence"),
                                                    rs.getLong("total_at_issue"),
                                                    rs.getLong("total_quantity"))));

    /**
     * 두 컬럼 중 한쪽만 적힌 행을 찾는다.
     *
     * <p>둘 다 {@code NULL}이면 더미데이터라 검사 대상이 아니고, 둘 다 있으면 위 항목이 본다. 한쪽만 있는 것은 컨슈머가
     * 한 컬럼만 적었다는 뜻이라 어느 쪽이든 위반이다.
     *
     * <p><b>양방향을 다 봐야 한다.</b> {@code issue_sequence IS NOT NULL}로 대상을 좁히면 순번만 빠진 행이 필터에
     * 걸러져 조용히 통과한다. 그래서 {@code <>}로 두 {@code NULL} 여부를 직접 비교한다.
     */
    private static final SequenceCheck SEQUENCE_HALF_WRITTEN =
            new SequenceCheck(
                    TOUCHED_COUNT_SQL,
                    """
                    SELECT COUNT(*) FROM coupon_issue
                    WHERE (issue_sequence IS NULL) <> (remaining_at_issue IS NULL)
                    """,
                    """
                    SELECT coupon_issue_id, issue_sequence, remaining_at_issue
                    FROM coupon_issue
                    WHERE (issue_sequence IS NULL) <> (remaining_at_issue IS NULL)
                    ORDER BY coupon_issue_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON_ISSUE,
                                    rs.getLong("coupon_issue_id"),
                                    "SEQUENCE_HALF_WRITTEN: 순번 %s, 잔여 %s"
                                            .formatted(
                                                    rs.getObject("issue_sequence"),
                                                    rs.getObject("remaining_at_issue"))));

    /**
     * 같은 순번을 두 발급 건이 받았는지 본다.
     *
     * <p>{@code INCR}은 원자적이라 Redis만으로는 나올 수 없는 상태다. 났다면 컨슈머 적재나 재시도 쪽 문제다.
     */
    private static final SequenceCheck SEQUENCE_DUPLICATED =
            new SequenceCheck(
                    SEQUENCED_COUPON_COUNT_SQL,
                    """
                    SELECT COUNT(*) FROM (
                        SELECT coupon_id
                        FROM coupon_issue
                        WHERE issue_sequence IS NOT NULL
                        GROUP BY coupon_id
                        HAVING COUNT(DISTINCT issue_sequence) <> COUNT(*)
                    ) duplicated
                    """,
                    """
                    SELECT coupon_id,
                           COUNT(*) AS issued,
                           COUNT(DISTINCT issue_sequence) AS distinct_sequence
                    FROM coupon_issue
                    WHERE issue_sequence IS NOT NULL
                    GROUP BY coupon_id
                    HAVING distinct_sequence <> issued
                    ORDER BY coupon_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON,
                                    rs.getLong("coupon_id"),
                                    "SEQUENCE_DUPLICATED: 발급 %d건인데 순번은 %d종"
                                            .formatted(
                                                    rs.getLong("issued"), rs.getLong("distinct_sequence"))));

    /**
     * 순번에 구멍이 났는지 본다. 최대 순번이 발급 건수보다 크면 그 사이 번호를 받은 건이 DB에 없다는 뜻이다.
     *
     * <p>원인은 둘이다. <b>보상</b>이면 정상 동작의 흔적이고(순번은 되돌리지 않으므로), <b>유실</b>이면 진짜 사고다. 둘을
     * DB만으로 가르지 못하지만 어느 쪽이든 알아야 할 신호라 위반으로 본다. 시연 기준으로는 보상 0건이 정상이다.
     *
     * <p>{@code MIN = 1}은 따로 보지 않는다. 중복이 없고({@code COUNT(DISTINCT) = COUNT(*)}) 최대가 건수와
     * 같으면, 서로 다른 값 N개의 최댓값이 N이므로 1..N이 강제된다.
     */
    private static final SequenceCheck SEQUENCE_GAP =
            new SequenceCheck(
                    SEQUENCED_COUPON_COUNT_SQL,
                    """
                    SELECT COUNT(*) FROM (
                        SELECT coupon_id
                        FROM coupon_issue
                        WHERE issue_sequence IS NOT NULL
                        GROUP BY coupon_id
                        HAVING MAX(issue_sequence) <> COUNT(*)
                    ) gapped
                    """,
                    """
                    SELECT coupon_id,
                           COUNT(*) AS issued,
                           MAX(issue_sequence) AS max_sequence
                    FROM coupon_issue
                    WHERE issue_sequence IS NOT NULL
                    GROUP BY coupon_id
                    HAVING max_sequence <> issued
                    ORDER BY coupon_id
                    LIMIT :limit
                    """,
                    (rs, rowNum) ->
                            Violation.of(
                                    ViolationTarget.COUPON,
                                    rs.getLong("coupon_id"),
                                    "SEQUENCE_GAP: 발급 %d건인데 최대 순번은 %d"
                                            .formatted(
                                                    rs.getLong("issued"), rs.getLong("max_sequence"))));

    private static final List<SequenceCheck> CHECKS =
            List.of(
                    SEQUENCE_STOCK_DIVERGED, SEQUENCE_HALF_WRITTEN, SEQUENCE_DUPLICATED, SEQUENCE_GAP);

    @Override
    public VerificationRule rule() {
        return VerificationRule.ISSUE_SEQUENCE_MISMATCH;
    }

    @Override
    public RuleOutcome check(NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        long checkedCount = 0;
        long violationCount = 0;
        List<Violation> violations = new ArrayList<>();

        for (SequenceCheck check : CHECKS) {
            checkedCount += RuleQueries.countOnce(jdbcTemplate, context, check.checkedSql());
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
