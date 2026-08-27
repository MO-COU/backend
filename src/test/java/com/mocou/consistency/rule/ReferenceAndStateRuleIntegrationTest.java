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
 * 참조 무결성과 상태·시각 규칙을 실제 MySQL에서 확인한다.
 *
 * <p>규모를 줄여 구조만 본다. 두 규칙 모두 비율이 아니라 조건식이라 규모를 줄여도 판정이 그대로 성립한다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class ReferenceAndStateRuleIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 23, 12, 0);
    private static final long GRACE_SECONDS = 300;

    /**
     * 만료 시각을 먼 미래로 둔다. 만료가 지난 {@code ISSUED} 행을 남기면 만료 배치가 그것을 집어 상태를 바꾸고 이력을 쌓는다.
     * 배치는 DB 전체를 보므로 이 클래스에서 스케줄러를 꺼도 다른 테스트의 컨텍스트가 남은 행을 처리한다.
     */
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    @Autowired private List<ConsistencyRule> rules;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;

    private VerificationContext context;

    @BeforeEach
    void seedNormalData() {
        context = new VerificationContext(BASE_TIME, GRACE_SECONDS, 1_000);
        // 회원을 여럿 둔다. UNIQUE (coupon_id, member_id) 때문에 같은 쿠폰에 같은 회원을 두 번 넣을 수 없어,
        // 위반을 주입하는 테스트마다 다른 회원을 써야 한다.
        for (long memberId = 1; memberId <= 5; memberId++) {
            jdbcTemplate.update(
                    "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                    memberId,
                    "user%d@mocou.test".formatted(memberId),
                    "회원" + memberId,
                    "010-0000-000%d".formatted(memberId),
                    Timestamp.valueOf(BASE_TIME.minusYears(1)));
        }
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at) VALUES (1, '테스트 쿠폰', ?, ?, 'OPEN', ?)",
                Timestamp.valueOf(BASE_TIME.minusDays(30)),
                Timestamp.valueOf(BASE_TIME.plusDays(30)),
                Timestamp.valueOf(BASE_TIME.minusDays(30)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (1, 10, 9)");
        insertIssue(1, 1, 1, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
    }

    @Test
    @DisplayName("정상 데이터에서는 두 규칙 모두 위반이 없다")
    void bothRulesPassOnCleanData() {
        // when, then
        assertThat(outcome(VerificationRule.ORPHAN_REFERENCE).violationCount()).isZero();
        assertThat(outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH).violationCount()).isZero();
    }

    @Test
    @DisplayName("참조 규칙은 자식 테이블 네 곳의 행을 모두 검사 대상으로 센다")
    void orphanRuleCountsEveryChildRow() {
        // when - 발급 1 × 2회(쿠폰·회원) + 이력 0 + 재고 1
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then
        assertThat(outcome.checkedCount()).isEqualTo(3);
    }

    /** 부모 하나가 사라지면 그것을 가리키던 자식이 한꺼번에 끊긴다. 위반은 값 하나로 묶여야 한다. */
    @Test
    @DisplayName("없는 쿠폰을 가리키는 발급 건이 여러 개여도 위반은 사라진 쿠폰 하나로 센다")
    void groupsOrphansByMissingValue() {
        // given - FK를 끄고 없는 쿠폰(999)을 가리키는 발급 3건을 넣는다
        withoutForeignKeyChecks(
                () -> {
                    insertIssue(901, 999, 1, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
                    insertIssue(902, 999, 2, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
                    insertIssue(903, 999, 3, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
                });

        // when
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON);
                            assertThat(violation.targetId()).isEqualTo(999);
                            assertThat(violation.detail()).contains("coupon_issue.coupon_id", "3건");
                        });
    }

    @Test
    @DisplayName("없는 회원을 가리키면 대상이 회원으로 기록된다")
    void reportsMissingMemberAsMemberTarget() {
        // given
        withoutForeignKeyChecks(
                () -> insertIssue(901, 1, 777, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES));

        // when
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.MEMBER);
                            assertThat(violation.targetId()).isEqualTo(777);
                        });
    }

    /**
     * 회원이 사라진 발급 건이다. 회원 정리 배치나 복원 스크립트가 발급을 남긴 채 회원만 지우면 생긴다.
     *
     * <p>위반은 <b>사라진 회원 하나</b>로 묶인다. 그 회원을 가리키던 발급이 몇 건이든 원인은 하나이기 때문이다.
     */
    @Test
    @DisplayName("없는 회원을 가리키는 발급 건을 검출한다")
    void detectsOrphanMemberReference() {
        // given - FK를 끄고 없는 회원(999)에게 발급 2건을 넣는다
        withoutForeignKeyChecks(
                () -> {
                    insertIssue(921, 1, 999, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
                    insertIssue(922, 1, 998, "ISSUED", BASE_TIME.minusDays(1), null, NEVER_EXPIRES);
                });

        // when
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then - 사라진 회원이 둘이므로 위반도 2건이다
        assertThat(outcome.violationCount()).isEqualTo(2);
        assertThat(outcome.violations())
                .allSatisfy(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.MEMBER);
                            assertThat(violation.detail()).contains("coupon_issue.member_id");
                        });
    }

    /**
     * 발급이 사라진 이력이다. 발급을 지우면서 이력을 함께 지우지 않으면 남는다.
     *
     * <p>정합성 검증이 이력을 근거로 삼으므로, 이것이 남아 있으면 <b>없는 발급의 상태 전이를 사실로 읽게 된다.</b>
     */
    @Test
    @DisplayName("없는 발급을 가리키는 이력을 검출한다")
    void detectsOrphanHistoryReference() {
        // given
        withoutForeignKeyChecks(() -> insertHistory(999, "UNISSUED", "ISSUED"));

        // when
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON_ISSUE);
                            assertThat(violation.targetId()).isEqualTo(999);
                            assertThat(violation.detail())
                                    .contains("coupon_issue_history.coupon_issue_id");
                        });
    }

    /**
     * 쿠폰이 사라진 재고다. 재고만 남으면 <b>아무도 발급받을 수 없는 재고</b>가 장부에 잡힌다.
     *
     * <p>재고 정합성 규칙({@code STOCK_MISMATCH})은 재고 행을 기준으로 세므로, 이런 행이 있으면 그 규칙의
     * 검사 대상 수도 함께 어긋난다.
     */
    @Test
    @DisplayName("없는 쿠폰을 가리키는 재고를 검출한다")
    void detectsOrphanStockReference() {
        // given
        withoutForeignKeyChecks(
                () ->
                        jdbcTemplate.update(
                                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                                        + " VALUES (999, 10, 10)"));

        // when
        RuleOutcome outcome = outcome(VerificationRule.ORPHAN_REFERENCE);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON);
                            assertThat(violation.targetId()).isEqualTo(999);
                            assertThat(violation.detail()).contains("coupon_stock.coupon_id");
                        });
    }

    /**
     * 사용을 취소하면서 {@code used_at}을 지우지 않은 흔적이다. 상태만 되돌리고 시각을 남겨두면 "언제 썼는지"가 남아
     * 이력과 어긋난다.
     */
    @Test
    @DisplayName("사용 상태가 아닌데 사용 시각이 있으면 검출한다")
    void detectsUnusedWithTimestamp() {
        // given - 1건 주입. 이 규칙은 행 단위로 세므로 검출도 1건이다
        insertIssue(911, 1, 2, "ISSUED", BASE_TIME.minusDays(2), BASE_TIME.minusDays(1), NEVER_EXPIRES);

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(911);
                            assertThat(violation.detail()).contains("UNUSED_WITH_TIMESTAMP");
                        });
    }

    /** 받기 전에 쓴 쿠폰이다. 발급과 사용이 서로 다른 경로로 적재되면 순서가 뒤집힐 수 있다. */
    @Test
    @DisplayName("사용 시각이 발급 시각보다 이르면 검출한다")
    void detectsUsedBeforeIssued() {
        // given
        insertIssue(912, 1, 3, "USED", BASE_TIME.minusDays(2), BASE_TIME.minusDays(3), NEVER_EXPIRES);

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(912);
                            assertThat(violation.detail()).contains("USED_BEFORE_ISSUED");
                        });
    }

    /**
     * 미래에 발급된 쿠폰이다.
     *
     * <p>판정 기준은 실제 현재 시각이 아니라 <b>검증 스냅샷 시각({@code T})</b>이다. 테스트의
     * {@code BASE_TIME}을 그 값으로 넘기고 있으므로, 그보다 뒤로 넣으면 실행하는 날짜와 무관하게 위반이 된다.
     */
    @Test
    @DisplayName("발급 시각이 기준 시각보다 뒤면 검출한다")
    void detectsFutureIssue() {
        // given
        insertIssue(913, 1, 4, "ISSUED", BASE_TIME.plusDays(1), null, NEVER_EXPIRES);

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(913);
                            assertThat(violation.detail()).contains("FUTURE_ISSUE");
                        });
    }

    /**
     * 기간이 남았는데 만료된 건이다.
     *
     * <p>만료가 밀리는 것({@code EXPIRY_OVERDUE})은 배치 지연으로 설명되지만 <b>이르게 만료되는 것은 설명할 수
     * 없다.</b> 그래서 유예를 주지 않고 {@code expires_at > T}이면 곧바로 위반으로 본다.
     */
    @Test
    @DisplayName("만료 시각이 남았는데 만료 상태면 검출한다")
    void detectsPrematureExpiry() {
        // given
        insertIssue(914, 1, 5, "EXPIRED", BASE_TIME.minusDays(2), null, BASE_TIME.plusDays(7));

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(914);
                            assertThat(violation.detail()).contains("PREMATURE_EXPIRY");
                        });
    }

    @Test
    @DisplayName("사용 상태인데 사용 시각이 없으면 검출한다")
    void detectsUsedWithoutTimestamp() {
        // given
        insertIssue(901, 1, 2, "USED", BASE_TIME.minusDays(2), null, NEVER_EXPIRES);

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(901);
                            assertThat(violation.detail()).isEqualTo("USED_WITHOUT_TIMESTAMP");
                        });
    }

    /** 한 행이 여러 항목을 동시에 어겨도 위반은 한 건이고, 어긴 항목은 상세에 모두 남는다. */
    @Test
    @DisplayName("한 행이 여러 항목을 어기면 위반 한 건에 항목을 모두 나열한다")
    void listsEveryBrokenItemForOneRow() {
        // given - 사용 상태인데 시각이 없고, 유효기간도 거꾸로다
        insertIssue(901, 1, 2, "USED", BASE_TIME.minusDays(2), null, BASE_TIME.minusDays(3));

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation ->
                                assertThat(violation.detail())
                                        .isEqualTo("USED_WITHOUT_TIMESTAMP,INVALID_VALIDITY"));
    }

    /** 만료 전환은 배치가 일괄 처리하므로 지연이 있다. 유예 안의 지연을 위반으로 잡으면 오탐이 된다. */
    @Test
    @DisplayName("만료가 지났어도 유예 안이면 위반이 아니다")
    void allowsExpiryDelayWithinGrace() {
        // given - 유예 300초 중 100초만 지난 ISSUED
        insertIssue(
                901,
                1,
                2,
                "ISSUED",
                BASE_TIME.minusDays(2),
                null,
                BASE_TIME.minusSeconds(GRACE_SECONDS - 200));

        // when, then
        assertThat(outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH).violationCount()).isZero();
    }

    @Test
    @DisplayName("유예를 넘겨 만료가 밀리면 검출한다")
    void detectsExpiryOverdueBeyondGrace() {
        // given - 유예 300초를 100초 넘긴 ISSUED
        insertIssue(
                901,
                1,
                2,
                "ISSUED",
                BASE_TIME.minusDays(2),
                null,
                BASE_TIME.minusSeconds(GRACE_SECONDS + 100));

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(violation -> assertThat(violation.detail()).isEqualTo("EXPIRY_OVERDUE"));
    }

    @Test
    @DisplayName("가입 전에 받은 쿠폰을 검출한다")
    void detectsIssueBeforeSignup() {
        // given - 회원 가입은 1년 전인데 발급이 2년 전
        insertIssue(901, 1, 2, "ISSUED", BASE_TIME.minusYears(2), null, NEVER_EXPIRES);

        // when
        RuleOutcome outcome = outcome(VerificationRule.STATE_TIMESTAMP_MISMATCH);

        // then
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> assertThat(violation.detail()).isEqualTo("ISSUED_BEFORE_SIGNUP"));
    }

    private RuleOutcome outcome(VerificationRule target) {
        return rules.stream()
                .filter(rule -> rule.rule() == target)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("규칙 구현이 없다: " + target))
                .check(namedJdbcTemplate, context);
    }

    /**
     * FK를 끈 채 작업을 실행한다. 고아 참조는 제약이 살아 있으면 만들 수 없다.
     *
     * <p>{@code FOREIGN_KEY_CHECKS}는 세션 변수이고 JdbcTemplate이 매번 풀에서 커넥션을 빌리므로, 하나의 커넥션 안에서
     * 끄고 넣고 되돌린다.
     */
    /** 발급 없이 이력만 넣는다. 고아 이력을 만들려면 FK를 끈 채 불러야 한다. */
    private void insertHistory(long issueId, String fromStatus, String toStatus) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history"
                        + " (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)"
                        + " VALUES (?, ?, ?, ?, ?)",
                issueId,
                fromStatus,
                toStatus,
                Timestamp.valueOf(BASE_TIME.minusDays(1)),
                "orphan-%d".formatted(issueId));
    }

    private void withoutForeignKeyChecks(Runnable work) {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            work.run();
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private void insertIssue(
            long issueId,
            long couponId,
            long memberId,
            String status,
            LocalDateTime issuedAt,
            LocalDateTime usedAt,
            LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                issueId,
                couponId,
                memberId,
                status,
                Timestamp.valueOf(issuedAt),
                usedAt == null ? null : Timestamp.valueOf(usedAt),
                Timestamp.valueOf(expiresAt));
    }
}
