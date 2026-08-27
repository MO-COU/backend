package com.mocou.loadtest;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class JdbcLoadTestResetRepository implements LoadTestResetRepository {

    private static final String COUPON_STATUS_SQL =
            "SELECT status FROM coupon WHERE coupon_id = :couponId";

    /**
     * 이력에는 {@code coupon_id}가 없어 발급 테이블과 이어 붙여 조건을 건다. {@code DELETE h}가 붙인 것 중
     * 이력 쪽만 지운다는 뜻이며, 생략하면 어느 테이블을 지울지 정하지 못해 문법 오류가 난다.
     *
     * <p>서브쿼리({@code WHERE coupon_issue_id IN (SELECT ...)})로도 되지만, 발급이 1만 건이면 목록도 1만
     * 개가 된다. 조인은 {@code idx_history_issue}를 타고 한 번에 붙는다.
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

    private static final String DELETE_VIOLATIONS_SQL = "DELETE FROM verification_violation";

    private static final String DELETE_RULE_RESULTS_SQL = "DELETE FROM verification_rule_result";

    private static final String DELETE_RUNS_SQL = "DELETE FROM verification_run";

    /**
     * 잔여를 총 재고로 되돌린다. 이미 같으면 MySQL이 값을 바꾸지 않아 0을 돌려주므로, 되돌린 결과를 알려면 갱신 건수가 아니라
     * 값을 다시 읽어야 한다.
     */
    private static final String RESTORE_STOCK_SQL =
            """
            UPDATE coupon_stock
            SET remaining_quantity = total_quantity
            WHERE coupon_id = :couponId
            """;

    private static final String SELECT_TOTAL_QUANTITY_SQL =
            "SELECT total_quantity FROM coupon_stock WHERE coupon_id = :couponId";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public String findStatus(long couponId) {
        List<String> found =
                jdbcTemplate.queryForList(
                        COUPON_STATUS_SQL, Map.of("couponId", couponId), String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public int deleteHistories(long couponId) {
        return update(DELETE_HISTORIES_SQL, couponId);
    }

    @Override
    public int deleteIssues(long couponId) {
        return update(DELETE_ISSUES_SQL, couponId);
    }

    @Override
    public int deleteFailureLogs(long couponId) {
        return update(DELETE_FAILURE_LOGS_SQL, couponId);
    }

    @Override
    public int deleteNotifications(long couponId) {
        return update(DELETE_NOTIFICATIONS_SQL, couponId);
    }

    @Override
    public int deleteAllVerificationRecords() {
        jdbcTemplate.update(DELETE_VIOLATIONS_SQL, Map.of());
        jdbcTemplate.update(DELETE_RULE_RESULTS_SQL, Map.of());
        return jdbcTemplate.update(DELETE_RUNS_SQL, Map.of());
    }

    @Override
    public int restoreStock(long couponId) {
        jdbcTemplate.update(RESTORE_STOCK_SQL, Map.of("couponId", couponId));

        Integer restored =
                jdbcTemplate.queryForObject(
                        SELECT_TOTAL_QUANTITY_SQL, Map.of("couponId", couponId), Integer.class);
        return restored == null ? 0 : restored;
    }

    private int update(String sql, long couponId) {
        return jdbcTemplate.update(sql, Map.of("couponId", couponId));
    }
}
