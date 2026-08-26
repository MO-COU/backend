package com.mocou.loadtest;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class LoadTestRunRepository {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbcTemplate;

    public LoadTestRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsRunning() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_issue_run WHERE status IN ('PENDING', 'RUNNING', 'SYNCING')",
                        Integer.class);
        return count != null && count > 0;
    }

    /** 실행 전 쿠폰 상태 확인함. 발급 이력 있으면 비교 불가. */
    public void validateCouponReady(long couponId, int expectedStock) {
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        CouponReadyState state =
                jdbcTemplate
                        .query(
                                """
                                SELECT c.status, c.open_at, c.close_at,
                                       s.total_quantity, s.remaining_quantity,
                                       (SELECT COUNT(*) FROM coupon_issue i WHERE i.coupon_id = c.coupon_id) AS issue_count
                                  FROM coupon c
                                  JOIN coupon_stock s ON s.coupon_id = c.coupon_id
                                 WHERE c.coupon_id = ?
                                """,
                                (resultSet, rowNum) ->
                                        new CouponReadyState(
                                                resultSet.getString("status"),
                                                resultSet.getTimestamp("open_at").toLocalDateTime(),
                                                resultSet.getTimestamp("close_at").toLocalDateTime(),
                                                resultSet.getInt("total_quantity"),
                                                resultSet.getInt("remaining_quantity"),
                                                resultSet.getLong("issue_count")),
                                couponId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        if (!"OPEN".equals(state.status())) {
            throw notReady("OPEN 상태인 쿠폰만 부하 테스트를 실행할 수 있습니다");
        }
        if (now.isBefore(state.openAt()) || !now.isBefore(state.closeAt())) {
            throw notReady("쿠폰 발급 가능 시간에만 부하 테스트를 실행할 수 있습니다");
        }
        if (state.totalQuantity() != expectedStock) {
            throw notReady("선택한 시나리오는 재고 " + expectedStock + "장인 쿠폰이 필요합니다");
        }
        if (state.remainingQuantity() != state.totalQuantity() || state.issueCount() > 0) {
            throw notReady("이미 발급 이력이 있는 쿠폰입니다. 새 회차를 만들거나 초기화해 주세요");
        }
    }

    private BusinessException notReady(String message) {
        return new BusinessException(ErrorCode.LOAD_TEST_COUPON_NOT_READY, message);
    }

    public long create(long couponId, LoadTestScenario scenario) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO coupon_issue_run
                                        (coupon_id, scenario_version, vus, ramp_up_seconds,
                                         requested_count, status, started_at)
                                    VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
                                    """,
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, couponId);
                    statement.setString(2, scenario.name());
                    statement.setInt(3, scenario.vus());
                    statement.setInt(4, scenario.rampUpSeconds());
                    // 실제 요청 수는 완료 후 저장함.
                    statement.setInt(5, 0);
                    statement.setTimestamp(6, Timestamp.valueOf(now));
                    return statement;
                },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "부하 테스트 실행 번호를 생성하지 못했습니다");
        }
        return key.longValue();
    }

    public void markSyncing(long runId, LoadTestRunResult result) {
        finish(runId, result, LoadTestRunStatus.SYNCING);
    }

    public void completeDbSync(long runId) {
        jdbcTemplate.update(
                "UPDATE coupon_issue_run SET status = 'SUCCESS', db_sync_finished_at = ? WHERE run_id = ?",
                Timestamp.valueOf(LocalDateTime.now(SERVICE_ZONE)),
                runId);
    }

    public void finish(long runId, LoadTestRunResult result, LoadTestRunStatus status) {
        jdbcTemplate.update(
                """
                UPDATE coupon_issue_run
                   SET requested_count = ?, issued_count = ?, failed_count = ?,
                       sold_out_count = ?, duplicate_count = ?, error_count = ?, p95_ms = ?,
                       status = ?, finished_at = ?
                 WHERE run_id = ?
                """,
                result.requestedCount(),
                result.issuedCount(),
                result.failedCount(),
                result.soldOutCount(),
                result.duplicateCount(),
                result.errorCount(),
                result.p95Ms(),
                status.name(),
                Timestamp.valueOf(LocalDateTime.now(SERVICE_ZONE)),
                runId);
    }

    public void fail(long runId) {
        jdbcTemplate.update(
                "UPDATE coupon_issue_run SET status = 'FAILED', finished_at = COALESCE(finished_at, ?) WHERE run_id = ?",
                Timestamp.valueOf(LocalDateTime.now(SERVICE_ZONE)),
                runId);
    }

    public LoadTestRunResponse find(long runId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT run_id, coupon_id, scenario_version, status, vus, ramp_up_seconds,
                               requested_count, issued_count, failed_count, sold_out_count,
                               duplicate_count, error_count, p95_ms, started_at, finished_at,
                               db_sync_finished_at
                          FROM coupon_issue_run
                         WHERE run_id = ?
                        """,
                        (resultSet, rowNum) ->
                                new LoadTestRunResponse(
                                        resultSet.getLong("run_id"),
                                        resultSet.getLong("coupon_id"),
                                        LoadTestScenario.valueOf(resultSet.getString("scenario_version")),
                                        LoadTestRunStatus.valueOf(resultSet.getString("status")),
                                        resultSet.getInt("vus"),
                                        resultSet.getInt("ramp_up_seconds"),
                                        resultSet.getInt("requested_count"),
                                        resultSet.getInt("issued_count"),
                                        resultSet.getInt("failed_count"),
                                        resultSet.getInt("sold_out_count"),
                                        resultSet.getInt("duplicate_count"),
                                        resultSet.getInt("error_count"),
                                        (Integer) resultSet.getObject("p95_ms"),
                                        toOffsetDateTime(resultSet.getTimestamp("started_at")),
                                        toOffsetDateTime(resultSet.getTimestamp("finished_at")),
                                        toOffsetDateTime(resultSet.getTimestamp("db_sync_finished_at")),
                                        null),
                        runId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_TEST_RUN_NOT_FOUND));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toLocalDateTime().atZone(SERVICE_ZONE).toOffsetDateTime();
    }

    private record CouponReadyState(
            String status,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            int totalQuantity,
            int remainingQuantity,
            long issueCount) {}
}
