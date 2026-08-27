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
 * 예약 순번 규칙이 실제로 위반을 잡는지 확인한다.
 *
 * <p>"불일치 0건"이 주장으로 성립하려면 그 0이 "위반이 없어서 0"인지 "못 잡아서 0"인지 구분돼야 한다. 위반을 일부러 심어
 * 검출을 확인하는 것이 그 구분이다.
 *
 * <p>총재고 10장짜리 쿠폰에 순번 1~3을 받은 발급 세 건으로 시작한다. 각 행의 순번과 잔여를 더하면 10이 된다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class IssueSequenceRuleIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 27, 12, 0);
    private static final LocalDateTime ISSUED_AT = BASE_TIME.minusDays(1);

    /** 만료 배치가 상태를 바꾸면 이력이 늘어 판정이 흔들린다. */
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    private static final long TOTAL_QUANTITY = 10;

    @Autowired private List<ConsistencyRule> rules;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;

    private VerificationContext context;

    @BeforeEach
    void seedSequencedIssues() {
        context = new VerificationContext(BASE_TIME, 300, 1_000);
        insertCoupon();
        for (int seq = 1; seq <= 3; seq++) {
            insertMember(seq);
            insertIssue(seq, seq, (long) seq, TOTAL_QUANTITY - seq);
        }
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
        // when - 행 단위 항목 둘(발급 3건)과 쿠폰 단위 항목 둘(쿠폰 1개)
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.checkedCount()).isEqualTo(3 + 3 + 1 + 1);
    }

    /** 합이 총재고보다 작아지는 경우다. 보상으로는 이렇게 될 수 없다 — 보상은 합을 키우기만 한다. */
    @Test
    @DisplayName("순번과 잔여의 합이 총재고보다 작으면 검출한다")
    void detectsSumBelowTotalQuantity() {
        // given - 2번 발급의 잔여를 1 줄인다
        jdbcTemplate.update(
                "UPDATE coupon_issue SET remaining_at_issue = remaining_at_issue - 1"
                        + " WHERE coupon_issue_id = 2");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON_ISSUE);
                            assertThat(violation.targetId()).isEqualTo(2);
                            assertThat(violation.detail()).contains("SEQUENCE_STOCK_DIVERGED");
                        });
    }

    /**
     * 앞 순번보다 합이 줄어드는 경우다. 합 자체는 총재고 이상이라 부등식만으로는 지나가고, 단조성 검사가 잡는다.
     *
     * <p>1번의 합을 키워 12로 만들면 2번(합 10)이 앞보다 작아진다.
     */
    @Test
    @DisplayName("순번 순으로 합이 줄어들면 검출한다")
    void detectsNonMonotonicSum() {
        // given
        jdbcTemplate.update(
                "UPDATE coupon_issue SET remaining_at_issue = remaining_at_issue + 2"
                        + " WHERE coupon_issue_id = 1");

        // when
        RuleOutcome outcome = outcome();

        // then - 합이 줄어든 2번이 지목된다
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations())
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(2);
                            assertThat(violation.detail()).contains("SEQUENCE_STOCK_DIVERGED");
                        });
    }

    /**
     * 보상이 일어난 뒤의 정상 데이터다. 보상은 재고를 되살리면서 순번은 되돌리지 않으므로 그 뒤 예약의 합이 커진다.
     *
     * <p>등식({@code 합 = 총재고})으로 검사하면 여기가 전부 위반으로 잡힌다. 부등식과 단조성으로 검사하는 이유다.
     */
    @Test
    @DisplayName("보상 뒤에 합이 커진 것은 위반으로 보지 않는다")
    void acceptsIncreasedSumAfterCompensation() {
        // given - 3번 예약 전에 보상 1건이 나 재고가 하나 되살아난 상황
        jdbcTemplate.update(
                "UPDATE coupon_issue SET remaining_at_issue = remaining_at_issue + 1"
                        + " WHERE coupon_issue_id = 3");

        // when
        RuleOutcome outcome = outcome();

        // then - 카운터 항목은 통과한다
        assertThat(outcome.violations())
                .noneSatisfy(
                        violation -> assertThat(violation.detail()).contains("SEQUENCE_STOCK_DIVERGED"));
    }

    @Test
    @DisplayName("잔여만 비어 있으면 검출한다")
    void detectsMissingRemaining() {
        // given
        jdbcTemplate.update(
                "UPDATE coupon_issue SET remaining_at_issue = NULL WHERE coupon_issue_id = 2");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(2);
                            assertThat(violation.detail()).contains("SEQUENCE_HALF_WRITTEN");
                        });
    }

    /** 반대 방향이다. 대상을 {@code issue_sequence IS NOT NULL}로 좁히면 이 행이 필터에 걸러져 조용히 통과한다. */
    @Test
    @DisplayName("순번만 비어 있어도 검출한다")
    void detectsMissingSequence() {
        // given
        jdbcTemplate.update("UPDATE coupon_issue SET issue_sequence = NULL WHERE coupon_issue_id = 2");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetId()).isEqualTo(2);
                            assertThat(violation.detail()).contains("SEQUENCE_HALF_WRITTEN");
                        });
    }

    @Test
    @DisplayName("같은 순번이 두 건이면 검출한다")
    void detectsDuplicatedSequence() {
        // given - 3번이 2번과 같은 순번을 받은 상태로 만든다(합은 유지해 다른 항목과 섞이지 않게)
        jdbcTemplate.update(
                "UPDATE coupon_issue SET issue_sequence = 2, remaining_at_issue = 8"
                        + " WHERE coupon_issue_id = 3");

        // when
        RuleOutcome outcome = outcome();

        // then
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON);
                            assertThat(violation.targetId()).isEqualTo(1);
                            assertThat(violation.detail()).contains("SEQUENCE_DUPLICATED");
                        });
    }

    @Test
    @DisplayName("순번에 구멍이 있으면 검출한다")
    void detectsSequenceGap() {
        // given - 가운데 발급 건이 사라진 상태
        jdbcTemplate.update("DELETE FROM coupon_issue WHERE coupon_issue_id = 2");

        // when
        RuleOutcome outcome = outcome();

        // then - 발급 2건인데 최대 순번은 3
        assertThat(outcome.violations())
                .anySatisfy(
                        violation -> {
                            assertThat(violation.targetType()).isEqualTo(ViolationTarget.COUPON);
                            assertThat(violation.detail()).contains("SEQUENCE_GAP", "최대 순번은 3");
                        });
    }

    /** 더미데이터 300만 건은 Redis를 거치지 않아 두 컬럼이 비어 있다. 결측이 아니라 검사 대상이 아니라는 뜻이다. */
    @Test
    @DisplayName("두 값이 모두 비어 있는 발급 건은 검사하지 않는다")
    void ignoresRowsWithoutSequence() {
        // given - 기존 세 건을 전부 더미데이터 모양으로 되돌린다
        jdbcTemplate.update(
                "UPDATE coupon_issue SET issue_sequence = NULL, remaining_at_issue = NULL");

        // when
        RuleOutcome outcome = outcome();

        // then - 검사 대상이 0이고 위반도 없다
        assertThat(outcome.checkedCount()).isZero();
        assertThat(outcome.violationCount()).isZero();
    }

    private RuleOutcome outcome() {
        return rules.stream()
                .filter(rule -> rule.rule() == VerificationRule.ISSUE_SEQUENCE_MISMATCH)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("규칙 구현이 없다"))
                .check(namedJdbcTemplate, context);
    }

    private void insertCoupon() {
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (1, '순번 테스트 쿠폰', ?, ?, 'OPEN', ?)",
                Timestamp.valueOf(BASE_TIME.minusDays(3)),
                Timestamp.valueOf(BASE_TIME.plusDays(3)),
                Timestamp.valueOf(BASE_TIME.minusDays(3)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                        + " VALUES (1, ?, ?)",
                TOTAL_QUANTITY,
                TOTAL_QUANTITY - 3);
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

    private void insertIssue(long issueId, long memberId, Long sequence, Long remaining) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, issue_sequence, remaining_at_issue,"
                        + "  status, issued_at, expires_at)"
                        + " VALUES (?, 1, ?, ?, ?, 'ISSUED', ?, ?)",
                issueId,
                memberId,
                sequence,
                remaining,
                Timestamp.valueOf(ISSUED_AT),
                Timestamp.valueOf(NEVER_EXPIRES));
    }
}
