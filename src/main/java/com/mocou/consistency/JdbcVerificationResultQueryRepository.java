package com.mocou.consistency;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVerificationResultQueryRepository
        implements VerificationResultQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcVerificationResultQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<VerificationResultResponse> findByRunId(long runId) {
        Optional<RunRow> run =
                jdbcTemplate
                .query(
                        """
                        SELECT run_id, issue_run_id, snapshot_at, verdict, started_at, finished_at
                        FROM verification_run
                        WHERE run_id = ?
                        """,
                        (resultSet, rowNumber) ->
                                new RunRow(
                                        resultSet.getLong("run_id"),
                                        getNullableLong(resultSet.getObject("issue_run_id")),
                                        resultSet.getString("verdict"),
                                        toLocalDateTime(resultSet.getTimestamp("snapshot_at")),
                                        resultSet.getTimestamp("started_at").toLocalDateTime(),
                                        toLocalDateTime(resultSet.getTimestamp("finished_at"))),
                        runId)
                .stream()
                .findFirst();
        if (run.isEmpty()) {
            return Optional.empty();
        }

        RunRow row = run.get();
        List<VerificationRuleResultResponse> rules = findRules(runId);
        return Optional.of(
                new VerificationResultResponse(
                        row.runId(),
                        row.issueRunId(),
                        row.finishedAt() == null ? "RUNNING" : "COMPLETED",
                        row.verdict(),
                        row.snapshotAt(),
                        row.startedAt(),
                        row.finishedAt(),
                        rules.stream()
                                .mapToLong(VerificationRuleResultResponse::checkedCount)
                                .sum(),
                        rules.stream()
                                .mapToLong(VerificationRuleResultResponse::violationCount)
                                .sum(),
                        rules));
    }

    private List<VerificationRuleResultResponse> findRules(long runId) {
        return jdbcTemplate.query(
                """
                SELECT rule_result_id, rule_name, status, checked_count,
                       violation_count, failure_reason
                FROM verification_rule_result
                WHERE run_id = ?
                ORDER BY rule_result_id
                """,
                (resultSet, rowNumber) -> {
                    long ruleResultId = resultSet.getLong("rule_result_id");
                    return new VerificationRuleResultResponse(
                            ruleResultId,
                            resultSet.getString("rule_name"),
                            resultSet.getString("status"),
                            resultSet.getLong("checked_count"),
                            resultSet.getLong("violation_count"),
                            resultSet.getString("failure_reason"),
                            findViolations(ruleResultId));
                },
                runId);
    }

    private List<VerificationViolationResponse> findViolations(long ruleResultId) {
        return jdbcTemplate.query(
                """
                SELECT violation_id, target_type, target_id, target_id2, detail
                FROM verification_violation
                WHERE rule_result_id = ?
                ORDER BY violation_id
                """,
                (resultSet, rowNumber) ->
                        new VerificationViolationResponse(
                                resultSet.getLong("violation_id"),
                                resultSet.getString("target_type"),
                                getNullableLong(resultSet.getObject("target_id")),
                                getNullableLong(resultSet.getObject("target_id2")),
                                resultSet.getString("detail")),
                ruleResultId);
    }

    private static Long getNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record RunRow(
            long runId,
            Long issueRunId,
            String verdict,
            LocalDateTime snapshotAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {}
}
