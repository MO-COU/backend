package com.mocou.consistency.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.ViolationTarget;
import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 이력 체인 규칙을 실제 MySQL에서 확인한다.
 *
 * <p>발급 두 건으로 시작한다. 1번은 발급만 된 상태(이력 1줄), 2번은 사용까지 간 상태(이력 2줄)다. 항목마다 판정 쿼리가 달라 위반도
 * 항목별로 하나씩 주입해 확인한다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class HistoryChainRuleIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 23, 12, 0);
    private static final LocalDateTime ISSUED_AT = BASE_TIME.minusDays(2);

    /** 만료 배치가 집어가지 않도록 먼 미래로 둔다. 배치가 상태를 바꾸면 이력이 늘어 판정이 흔들린다. */
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    @Autowired private List<ConsistencyRule> rules;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;

    private VerificationContext context;

    @BeforeEach
    void seedNormalData() {
        context = new VerificationContext(BASE_TIME, 300, 1_000);
        insertMember(1);
        insertMember(2);
        insertCoupon();

        // 1번 발급: ISSUED 상태, 이력 한 줄
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);

        // 2번 발급: USED 상태, 이력 두 줄
        insertIssue(2, 2, "USED", ISSUED_AT.plusHours(3));
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(202, 2, "ISSUED", "USED", ISSUED_AT.plusHours(3));
    }

    @Test
    @DisplayName("정상 데이터에서는 위반이 없다")
    void passesOnCleanData() {
        // when, then
        assertThat(outcome().violationCount()).isZero();
    }

    @Test
    @DisplayName("검사 대상은 항목별 대상 수를 합산한다")
    void checkedCountSumsEveryItemTarget() {
        // when - 발급 2건짜리 항목 둘 + 이력 3건짜리 항목 둘
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.checkedCount()).isEqualTo(2 + 2 + 3 + 3);
    }

    @Test
    @DisplayName("최초 발급 이력이 없으면 검출한다")
    void detectsMissingInitialHistory() {
        // given - 1번 발급의 최초 이력을 지운다
        jdbcTemplate.update("DELETE FROM coupon_issue_history WHERE history_id = 101");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON_ISSUE);
                            assertThat(violation.targetId()).isEqualTo(1);
                            assertThat(violation.detail()).contains("MISSING_INITIAL_HISTORY", "0건");
                        });
    }

    @Test
    @DisplayName("최초 발급 이력이 두 건이어도 검출한다")
    void detectsDuplicatedInitialHistory() {
        // given
        insertHistory(102, 1, "UNISSUED", "ISSUED", ISSUED_AT.plusMinutes(1));

        // when
        RuleOutcome outcome = outcome();

        // then - 최초 이력 2건이면서 체인도 끊긴다(ISSUED 다음에 UNISSUED에서 출발)
        assertThat(outcome.violations())
                .anySatisfy(
                        violation ->
                                assertThat(violation.detail()).contains("MISSING_INITIAL_HISTORY", "2건"));
    }

    @Test
    @DisplayName("마지막 이력과 현재 상태가 다르면 검출한다")
    void detectsFinalStatusMismatch() {
        // given - 이력은 USED로 끝나는데 상태만 EXPIRED로 바꾼다
        jdbcTemplate.update("UPDATE coupon_issue SET status = 'EXPIRED' WHERE coupon_issue_id = 2");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(2);
                            assertThat(violation.detail())
                                    .contains("FINAL_STATUS_MISMATCH", "EXPIRED", "USED");
                        });
    }

    /** 앞 이력의 도착 상태가 다음 이력의 출발 상태와 달라진 경우다. 중간 전이가 기록되지 않았다는 뜻이다. */
    @Test
    @DisplayName("이력 사이가 끊기면 끊긴 줄을 가리켜 검출한다")
    void detectsBrokenChain() {
        // given - 2번 발급의 두 번째 이력 출발 상태를 EXPIRED로 바꾼다
        jdbcTemplate.update(
                "UPDATE coupon_issue_history SET from_status = 'EXPIRED' WHERE history_id = 202");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetType())
                                    .isEqualTo(ViolationTarget.COUPON_ISSUE_HISTORY);
                            assertThat(violation.targetId()).isEqualTo(202);
                            assertThat(violation.detail()).contains("BROKEN_CHAIN", "ISSUED", "EXPIRED");
                        });
    }

    @Test
    @DisplayName("이력이 발급보다 이르면 검출한다")
    void detectsHistoryBeforeIssue() {
        // given
        jdbcTemplate.update(
                "UPDATE coupon_issue_history SET changed_at = ? WHERE history_id = 101",
                Timestamp.valueOf(ISSUED_AT.minusDays(1)));

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetType())
                                    .isEqualTo(ViolationTarget.COUPON_ISSUE_HISTORY);
                            assertThat(violation.targetId()).isEqualTo(101);
                            assertThat(violation.detail()).contains("HISTORY_BEFORE_ISSUE");
                        });
    }

    /** 항목마다 판정 쿼리가 달라 결과를 합칠 수 없다. 한 발급 건이 두 항목을 어기면 위반도 두 건이다. */
    @Test
    @DisplayName("한 발급 건이 두 항목을 어기면 위반을 두 건으로 센다")
    void countsEachBrokenItemSeparately() {
        // given - 최초 이력을 지우면 최초 이력 없음 + 최종 상태 불일치가 함께 걸린다
        jdbcTemplate.update("DELETE FROM coupon_issue_history WHERE history_id = 201");
        jdbcTemplate.update("DELETE FROM coupon_issue_history WHERE history_id = 202");
        insertHistory(203, 2, "ISSUED", "EXPIRED", ISSUED_AT.plusHours(3));

        // when
        RuleOutcome outcome = outcome();

        // then - 최초 이력 없음(발급 2번) + 최종 상태 불일치(USED vs EXPIRED)
        assertThat(outcome.violationCount()).isEqualTo(2);
        assertThat(outcome.violations()).hasSize(2);
    }

    private RuleOutcome outcome() {
        return rules.stream()
                .filter(rule -> rule.rule() == VerificationRule.HISTORY_MISMATCH)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("규칙 구현이 없다"))
                .check(namedJdbcTemplate, context);
    }

    private void insertMember(long memberId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                memberId,
                "user%d@mocou.test".formatted(memberId),
                "회원" + memberId,
                "010-0000-000%d".formatted(memberId),
                Timestamp.valueOf(BASE_TIME.minusYears(1)));
    }

    private void insertCoupon() {
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (1, '테스트 쿠폰', ?, ?, 'OPEN', ?)",
                Timestamp.valueOf(BASE_TIME.minusDays(30)),
                Timestamp.valueOf(BASE_TIME.plusDays(30)),
                Timestamp.valueOf(BASE_TIME.minusDays(30)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (1, 10, 8)");
    }

    private void insertIssue(long issueId, long memberId, String status, LocalDateTime usedAt) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, ?)",
                issueId,
                memberId,
                status,
                Timestamp.valueOf(ISSUED_AT),
                usedAt == null ? null : Timestamp.valueOf(usedAt),
                Timestamp.valueOf(NEVER_EXPIRES));
    }

    private void insertHistory(
            long historyId, long issueId, String fromStatus, String toStatus, LocalDateTime changedAt) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history"
                        + " (history_id, coupon_issue_id, from_status, to_status, changed_at, idempotency_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                historyId,
                issueId,
                fromStatus,
                toStatus,
                Timestamp.valueOf(changedAt),
                "%s:%d".formatted(toStatus, historyId));
    }
}
