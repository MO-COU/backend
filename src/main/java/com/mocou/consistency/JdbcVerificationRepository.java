package com.mocou.consistency;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증 실행을 {@code verification_run} → {@code rule_result} → {@code violation} 순서로 적재한다.
 *
 * <p>순서는 FK가 강제한다. 부모 행의 번호가 {@code AUTO_INCREMENT}라 넣어봐야 값을 알 수 있고, 그 값을 자식에 넘겨야 한다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcVerificationRepository implements VerificationRepository {

    /** 시작 시점에는 스냅샷 시각과 판정을 모른다. 두 컬럼은 V7에서 {@code NULL}을 허용한다. */
    private static final String INSERT_RUN_SQL =
            """
            INSERT INTO verification_run (issue_run_id, started_at)
            VALUES (?, ?)
            """;

    private static final String COMPLETE_RUN_SQL =
            """
            UPDATE verification_run
               SET snapshot_at = ?, verdict = ?, finished_at = ?
             WHERE run_id = ?
            """;

    private static final String FAIL_RUN_SQL =
            """
            UPDATE verification_run
               SET verdict = 'ERROR', finished_at = ?
             WHERE run_id = ?
            """;

    private static final String INSERT_RULE_RESULT_SQL =
            """
            INSERT INTO verification_rule_result
                (run_id, rule_name, status, checked_count, violation_count, failure_reason)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_VIOLATION_SQL =
            """
            INSERT INTO verification_violation
                (rule_result_id, target_type, target_id, target_id2, detail, detected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String COUNT_RUNNING_SQL =
            """
            SELECT COUNT(*) FROM verification_run
             WHERE finished_at IS NULL AND started_at > ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasRunningSince(LocalDateTime startedAfter) {
        Long running =
                jdbcTemplate.queryForObject(
                        COUNT_RUNNING_SQL, Long.class, Timestamp.valueOf(startedAfter));
        return running != null && running > 0;
    }

    @Override
    public long startRun(Long issueRunId, LocalDateTime startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(INSERT_RUN_SQL, Statement.RETURN_GENERATED_KEYS);
                    // 전체 검증이면 대응하는 발급 실행이 없어 NULL이다(V6에서 허용).
                    if (issueRunId == null) {
                        statement.setNull(1, Types.BIGINT);
                    } else {
                        statement.setLong(1, issueRunId);
                    }
                    statement.setTimestamp(2, Timestamp.valueOf(startedAt));
                    return statement;
                },
                keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("verification_run의 생성 키를 받지 못했다");
        }
        return key.longValue();
    }

    /** 실행 하나의 기록이 한 트랜잭션이다. 중간에 끊기면 결과가 반쯤 남아 리포트가 거짓을 말한다. */
    @Override
    @Transactional
    public void completeRun(long runId, VerificationResult result) {
        jdbcTemplate.update(
                COMPLETE_RUN_SQL,
                Timestamp.valueOf(result.snapshotAt()),
                result.verdict().name(),
                Timestamp.valueOf(result.finishedAt()),
                runId);

        for (RuleOutcome outcome : result.outcomes()) {
            long ruleResultId = insertRuleResult(runId, outcome);
            insertViolations(ruleResultId, outcome, result.snapshotAt());
        }
    }

    @Override
    public void failRun(long runId, LocalDateTime finishedAt) {
        jdbcTemplate.update(FAIL_RUN_SQL, Timestamp.valueOf(finishedAt), runId);
    }

    private long insertRuleResult(long runId, RuleOutcome outcome) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    INSERT_RULE_RESULT_SQL, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, runId);
                    statement.setString(2, outcome.rule().name());
                    statement.setString(3, outcome.status().name());
                    statement.setLong(4, outcome.checkedCount());
                    statement.setLong(5, outcome.violationCount());
                    statement.setString(6, outcome.failureReason());
                    return statement;
                },
                keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("verification_rule_result의 생성 키를 받지 못했다");
        }
        return key.longValue();
    }

    /**
     * 위반 상세는 묶어서 보낸다. 규칙 7개가 각각 상한(1000건)까지 채우면 최악 7000행이라, 한 건씩 넣으면 왕복이 그만큼 발생한다.
     */
    private void insertViolations(
            long ruleResultId, RuleOutcome outcome, LocalDateTime detectedAt) {
        if (outcome.violations().isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(outcome.violations().size());
        for (Violation violation : outcome.violations()) {
            rows.add(
                    new Object[] {
                        ruleResultId,
                        violation.targetType().name(),
                        violation.targetId(),
                        violation.targetId2(),
                        violation.detail(),
                        Timestamp.valueOf(detectedAt)
                    });
        }
        jdbcTemplate.batchUpdate(INSERT_VIOLATION_SQL, rows);
    }
}
