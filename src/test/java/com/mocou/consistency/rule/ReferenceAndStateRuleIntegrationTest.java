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
