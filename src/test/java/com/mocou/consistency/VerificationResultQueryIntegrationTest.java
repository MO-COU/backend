package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.support.MySqlContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class VerificationResultQueryIntegrationTest extends MySqlContainerTest {

    @Autowired private VerificationResultQueryService service;

    @BeforeEach
    void clearVerificationResults() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_rule_result");
        jdbcTemplate.update("DELETE FROM verification_run");
    }

    @Test
    void readsCompletedResultWithRulesAndViolations() {
        jdbcTemplate.update(
                """
                INSERT INTO verification_run
                    (run_id, issue_run_id, snapshot_at, verdict, started_at, finished_at)
                VALUES (101, NULL, '2026-08-24 09:01:00', 'FAIL',
                        '2026-08-24 09:00:00', '2026-08-24 09:02:00')
                """);
        jdbcTemplate.update(
                """
                INSERT INTO verification_rule_result
                    (rule_result_id, run_id, rule_name, status, checked_count,
                     violation_count, failure_reason)
                VALUES (201, 101, 'STOCK_MISMATCH', 'CHECKED', 10000, 1, NULL)
                """);
        jdbcTemplate.update(
                """
                INSERT INTO verification_violation
                    (violation_id, rule_result_id, target_type, target_id,
                     target_id2, detail, detected_at)
                VALUES (301, 201, 'COUPON', 1, NULL, '재고 불일치',
                        '2026-08-24 09:01:00')
                """);

        VerificationResultResponse result = service.getResult(101L);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.verdict()).isEqualTo("FAIL");
        assertThat(result.checkedCount()).isEqualTo(10_000);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().getFirst().violations()).hasSize(1);
    }

    @Test
    void readsRunningResultBeforeRulesAreStored() {
        jdbcTemplate.update(
                """
                INSERT INTO verification_run (run_id, issue_run_id, started_at)
                VALUES (102, NULL, '2026-08-24 09:00:00')
                """);

        VerificationResultResponse result = service.getResult(102L);

        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.verdict()).isNull();
        assertThat(result.rules()).isEmpty();
    }
}
