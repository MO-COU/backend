package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 실행기가 판정 결과를 세 테이블에 그대로 남기는지 확인한다.
 *
 * <p>규칙별 통합 테스트는 {@link RuleOutcome}까지만 본다. 판정을 옳게 계산해도 적재에서 컬럼을 빠뜨리면 리포트만 조용히
 * 거짓말을 하므로, DB에 실제로 무엇이 적혔는지를 여기서 따로 확인한다.
 *
 * <p>쿠폰 상태를 {@code CLOSED}로 둬 Redis 규칙의 검사 대상을 비운다. 그러면 Redis 없이도 전 규칙이 돌아 이 클래스는
 * 적재만 보는 데 집중할 수 있다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class ConsistencyVerifierIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 1;

    @Autowired private ConsistencyVerifier verifier;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * 항상 터지는 규칙. 스프링이 {@code List<ConsistencyRule>}에 넣어주므로 실행기는 진짜 규칙과 구별하지 못한다.
     *
     * <p>실패를 켜고 끄는 스위치를 둔 이유는 스프링 컨텍스트를 하나로 유지하기 위해서다. 정상용과 실패용 클래스를 나누면
     * 컨테이너와 컨텍스트가 두 벌 뜬다.
     */
    static class ExplodingRule implements ConsistencyRule {

        static boolean armed = false;

        @Override
        public VerificationRule rule() {
            return VerificationRule.TOOL_RELIABILITY;
        }

        @Override
        public RuleOutcome check(NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
            if (armed) {
                throw new IllegalStateException("주입한 실패");
            }
            return RuleOutcome.passed(rule(), 0);
        }
    }

    @TestConfiguration
    static class ExplodingRuleConfig {
        @Bean
        ExplodingRule explodingRule() {
            return new ExplodingRule();
        }
    }

    @BeforeEach
    void clearVerificationHistory() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_rule_result");
        jdbcTemplate.update("DELETE FROM verification_run");
        ExplodingRule.armed = false;
    }

    @AfterEach
    void disarm() {
        ExplodingRule.armed = false;
    }

    @Test
    @DisplayName("위반이 없으면 PASS로 남고 규칙 결과가 전부 CHECKED로 적재된다")
    void recordsPassWithEveryRuleChecked() {
        // given
        long runId = verifier.startRun(null);

        // when
        verifier.runAndComplete(runId);

        // then
        Map<String, Object> run = selectRun(runId);
        assertThat(run.get("verdict")).isEqualTo("PASS");
        assertThat(run.get("snapshot_at")).isNotNull();
        assertThat(run.get("finished_at")).isNotNull();

        List<Map<String, Object>> results = selectRuleResults(runId);
        assertThat(results).hasSize(VerificationRule.values().length);
        assertThat(results).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("CHECKED"));
        assertThat(results).allSatisfy(row -> assertThat(row.get("failure_reason")).isNull());
        assertThat(countViolations(runId)).isZero();
    }

    @Test
    @DisplayName("위반이 있으면 FAIL로 남고 상세가 그 규칙 결과에 매달린다")
    void recordsFailWithViolationDetailLinkedToItsRule() {
        // given - 총재고 10인데 발급 0건에 잔여 5. 10 != 0 + 5 이라 STOCK_MISMATCH 1건이다
        insertCoupon();
        insertStock(10, 5);
        long runId = verifier.startRun(null);

        // when
        verifier.runAndComplete(runId);

        // then
        assertThat(selectRun(runId).get("verdict")).isEqualTo("FAIL");

        Map<String, Object> stockResult = selectRuleResult(runId, VerificationRule.STOCK_MISMATCH);
        assertThat(stockResult.get("status")).isEqualTo("CHECKED");
        assertThat(stockResult.get("violation_count")).isEqualTo(1L);

        // 상세가 다른 규칙이 아니라 STOCK_MISMATCH 결과에 달려야 리포트에서 원인을 짚을 수 있다
        List<Map<String, Object>> violations =
                namedJdbcTemplate.queryForList(
                        """
                        SELECT v.target_type, v.target_id, v.detail
                        FROM verification_violation v
                        WHERE v.rule_result_id = :ruleResultId
                        """,
                        Map.of("ruleResultId", stockResult.get("rule_result_id")));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("target_type")).isEqualTo("COUPON");
        assertThat(violations.get(0).get("target_id")).isEqualTo(COUPON_ID);
    }

    @Test
    @DisplayName("규칙 하나가 실패하면 ERROR로 남고 나머지 규칙은 그대로 실행된다")
    void recordsErrorWhenOneRuleFailsButKeepsRunningTheRest() {
        // given
        ExplodingRule.armed = true;
        long runId = verifier.startRun(null);

        // when
        verifier.runAndComplete(runId);

        // then - 위반이 0건이어도 판정할 수 없으므로 PASS가 아니다
        assertThat(selectRun(runId).get("verdict")).isEqualTo("ERROR");

        Map<String, Object> failed = selectRuleResult(runId, VerificationRule.TOOL_RELIABILITY);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat((String) failed.get("failure_reason")).contains("주입한 실패");

        // 실패한 규칙 앞뒤의 규칙이 함께 죽지 않아야 한 번의 실행에서 얻을 것을 다 얻는다
        List<Map<String, Object>> results = selectRuleResults(runId);
        assertThat(results).hasSize(VerificationRule.values().length);
        assertThat(results)
                .filteredOn(row -> !"TOOL_RELIABILITY".equals(row.get("rule_name")))
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("CHECKED"));
    }

    @Test
    @DisplayName("실행을 시작하면 진행 중 표시가 남고 끝나야 채워진다")
    void marksRunAsInProgressUntilItFinishes() {
        // when
        long runId = verifier.startRun(null);

        // then - 대시보드가 "돌고 있다"를 알 수 있어야 한다
        Map<String, Object> started = selectRun(runId);
        assertThat(started.get("started_at")).isNotNull();
        assertThat(started.get("finished_at")).isNull();
        assertThat(started.get("verdict")).isNull();
        assertThat(started.get("snapshot_at")).isNull();

        // when
        verifier.runAndComplete(runId);

        // then
        assertThat(selectRun(runId).get("finished_at")).isNotNull();
    }

    private Map<String, Object> selectRun(long runId) {
        return namedJdbcTemplate.queryForMap(
                "SELECT * FROM verification_run WHERE run_id = :runId", Map.of("runId", runId));
    }

    private List<Map<String, Object>> selectRuleResults(long runId) {
        return namedJdbcTemplate.queryForList(
                "SELECT * FROM verification_rule_result WHERE run_id = :runId ORDER BY rule_name",
                Map.of("runId", runId));
    }

    private Map<String, Object> selectRuleResult(long runId, VerificationRule rule) {
        return namedJdbcTemplate.queryForMap(
                """
                SELECT * FROM verification_rule_result
                WHERE run_id = :runId AND rule_name = :ruleName
                """,
                Map.of("runId", runId, "ruleName", rule.name()));
    }

    private long countViolations(long runId) {
        Long count =
                namedJdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*) FROM verification_violation v
                        JOIN verification_rule_result r ON r.rule_result_id = v.rule_result_id
                        WHERE r.run_id = :runId
                        """,
                        Map.of("runId", runId),
                        Long.class);
        return count == null ? 0 : count;
    }

    /** {@code CLOSED}로 둔다. Redis 규칙은 {@code OPEN}만 보므로 검사 대상이 비어 Redis 없이 돌아간다. */
    private void insertCoupon() {
        namedJdbcTemplate.update(
                """
                INSERT INTO coupon (coupon_id, name, open_at, close_at, status)
                VALUES (:couponId, '검증 적재 테스트', :openAt, :closeAt, 'CLOSED')
                """,
                Map.of(
                        "couponId", COUPON_ID,
                        "openAt", LocalDateTime.of(2026, 8, 1, 0, 0),
                        "closeAt", LocalDateTime.of(2026, 8, 2, 0, 0)));
    }

    private void insertStock(int total, int remaining) {
        namedJdbcTemplate.update(
                """
                INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)
                VALUES (:couponId, :total, :remaining)
                """,
                Map.of("couponId", COUPON_ID, "total", total, "remaining", remaining));
    }
}
