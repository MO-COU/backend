package com.mocou.coupon;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class JdbcCouponRoundRepository implements CouponRoundRepository {

    private static final String NEXT_ROUND_NUMBER_SQL =
            "SELECT COALESCE(MAX(coupon_id), 0) + 1 FROM coupon";

    /** 상태는 항상 {@code OPEN}이다. 오픈 여부 판정은 Redis가 {@code open_at}으로 한다. */
    private static final String INSERT_COUPON_SQL =
            """
            INSERT INTO coupon (coupon_id, name, open_at, close_at, status)
            VALUES (:couponId, :name, :openAt, :closeAt, 'OPEN')
            """;

    /** 발급 이력이 없는 새 회차라 잔여 재고가 총 재고와 같다. */
    private static final String INSERT_STOCK_SQL =
            """
            INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)
            VALUES (:couponId, :totalQuantity, :totalQuantity)
            """;

    private static final String FIND_STATUS_SQL =
            "SELECT status FROM coupon WHERE coupon_id = :couponId";

    /**
     * 이력에는 {@code coupon_id}가 없어 발급 테이블과 이어 붙여 조건을 건다. {@code DELETE h}가 붙인
     * 것 중 이력 쪽만 지운다는 뜻이며, 생략하면 어느 테이블을 지울지 정하지 못해 문법 오류가 난다.
     *
     * <p>서브쿼리({@code WHERE coupon_issue_id IN (SELECT ...)})로도 되지만, 발급이 1만 건이면
     * 목록도 1만 개가 된다. 조인은 {@code idx_history_issue}를 타고 한 번에 붙는다.
     */
    private static final String DELETE_HISTORIES_SQL =
            """
            DELETE h FROM coupon_issue_history h
            JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
            WHERE i.coupon_id = :couponId
            """;

    private static final String DELETE_ISSUES_SQL =
            "DELETE FROM coupon_issue WHERE coupon_id = :couponId";

    private static final String DELETE_FAILURE_LOGS_SQL =
            "DELETE FROM issue_failure_log WHERE coupon_id = :couponId";

    private static final String DELETE_NOTIFICATIONS_SQL =
            "DELETE FROM notification WHERE coupon_id = :couponId";

    /**
     * 검증 기록은 <b>이 회차 것만</b> 지운다.
     *
     * <p>{@code verification_run.issue_run_id}가 {@code NULL}인 실행은 더미데이터 300만 건을
     * 대상으로 한 검증이라 이 회차와 무관하다. 조건 없이 비우면 그 기록까지 함께 사라진다.
     *
     * <p>위반 → 규칙 결과 → 실행 → 발급 실행으로 네 단계를 거슬러 올라간다. 아래 셋은 서로 순서를
     * 지켜야 한다 - 자식을 먼저 지우지 않으면 FK에 막힌다.
     */
    private static final String DELETE_VIOLATIONS_SQL =
            """
            DELETE v FROM verification_violation v
            JOIN verification_rule_result r ON r.rule_result_id = v.rule_result_id
            JOIN verification_run run ON run.run_id = r.run_id
            JOIN coupon_issue_run cir ON cir.run_id = run.issue_run_id
            WHERE cir.coupon_id = :couponId
            """;

    private static final String DELETE_RULE_RESULTS_SQL =
            """
            DELETE r FROM verification_rule_result r
            JOIN verification_run run ON run.run_id = r.run_id
            JOIN coupon_issue_run cir ON cir.run_id = run.issue_run_id
            WHERE cir.coupon_id = :couponId
            """;

    private static final String DELETE_VERIFICATION_RUNS_SQL =
            """
            DELETE run FROM verification_run run
            JOIN coupon_issue_run cir ON cir.run_id = run.issue_run_id
            WHERE cir.coupon_id = :couponId
            """;

    private static final String DELETE_ISSUE_RUNS_SQL =
            "DELETE FROM coupon_issue_run WHERE coupon_id = :couponId";

    private static final String DELETE_STOCK_SQL =
            "DELETE FROM coupon_stock WHERE coupon_id = :couponId";

    private static final String DELETE_COUPON_SQL =
            "DELETE FROM coupon WHERE coupon_id = :couponId";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public long nextRoundNumber() {
        Long next = jdbcTemplate.queryForObject(NEXT_ROUND_NUMBER_SQL, Map.of(), Long.class);
        return next == null ? 1 : next;
    }

    @Override
    public void insertRound(
            long couponId,
            String name,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            int totalQuantity) {
        jdbcTemplate.update(
                INSERT_COUPON_SQL,
                Map.of(
                        "couponId", couponId,
                        "name", name,
                        "openAt", openAt,
                        "closeAt", closeAt));
        jdbcTemplate.update(
                INSERT_STOCK_SQL, Map.of("couponId", couponId, "totalQuantity", totalQuantity));
    }

    @Override
    public String findStatus(long couponId) {
        List<String> found =
                jdbcTemplate.queryForList(
                        FIND_STATUS_SQL, Map.of("couponId", couponId), String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * FK가 정한 차례대로 지운다. 자식을 먼저 지우지 않으면 {@code ERROR 1451}로 막힌다.
     *
     * <p>{@code coupon}을 참조하는 테이블은 {@code coupon_stock}·{@code coupon_issue}·
     * {@code notification}·{@code coupon_issue_run} 넷이고, 넷을 모두 치운 뒤라야 쿠폰이 지워진다.
     * {@code issue_failure_log}는 FK가 없지만 이 회차의 기록이라 함께 지운다.
     */
    @Override
    public CouponRoundDeleteResult deleteRound(long couponId) {
        int histories = update(DELETE_HISTORIES_SQL, couponId);
        int issues = update(DELETE_ISSUES_SQL, couponId);
        int failureLogs = update(DELETE_FAILURE_LOGS_SQL, couponId);
        int notifications = update(DELETE_NOTIFICATIONS_SQL, couponId);

        update(DELETE_VIOLATIONS_SQL, couponId);
        update(DELETE_RULE_RESULTS_SQL, couponId);
        int verificationRuns = update(DELETE_VERIFICATION_RUNS_SQL, couponId);

        int issueRuns = update(DELETE_ISSUE_RUNS_SQL, couponId);
        update(DELETE_STOCK_SQL, couponId);
        update(DELETE_COUPON_SQL, couponId);

        return new CouponRoundDeleteResult(
                couponId, issues, histories, failureLogs, notifications, verificationRuns, issueRuns);
    }

    private int update(String sql, long couponId) {
        return jdbcTemplate.update(sql, Map.of("couponId", couponId));
    }
}
