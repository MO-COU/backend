package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleOutcomeTest {

    private static final Violation SAMPLE =
            Violation.of(ViolationTarget.COUPON, 1L, "총재고 10000, 발급 10001");

    @Test
    @DisplayName("검사를 마친 결과는 상태가 CHECKED다")
    void checkedOutcomeKeepsStatus() {
        // when
        RuleOutcome outcome = RuleOutcome.passed(VerificationRule.OVER_ISSUE, 301);

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.CHECKED);
        assertThat(outcome.violationCount()).isZero();
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    @DisplayName("위반을 찾은 결과는 건수와 표본을 함께 갖는다")
    void violatedOutcomeKeepsCountAndSamples() {
        // when
        RuleOutcome outcome =
                RuleOutcome.violated(VerificationRule.OVER_ISSUE, 301, 1, List.of(SAMPLE));

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.CHECKED);
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations()).containsExactly(SAMPLE);
    }

    @Test
    @DisplayName("실행에 실패한 결과는 사유를 남긴다")
    void failedOutcomeKeepsReason() {
        // when
        RuleOutcome outcome =
                RuleOutcome.failed(VerificationRule.HISTORY_MISMATCH, "Lock wait timeout exceeded");

        // then
        assertThat(outcome.status()).isEqualTo(RuleStatus.FAILED);
        assertThat(outcome.violationCount()).isZero();
        assertThat(outcome.failureReason()).contains("Lock wait timeout");
    }

    /**
     * 데이터가 비어 검사 대상이 없는 정상 실행과 실패는 건수가 똑같이 0이다. 상태를 보지 않으면 규칙이 통째로 깨진 실행이 정상으로 보인다.
     */
    @Test
    @DisplayName("검사 대상이 0건인 정상 실행과 실패는 건수로 구분되지 않는다")
    void emptyTargetAndFailureShareTheSameCounts() {
        // when
        RuleOutcome empty = RuleOutcome.passed(VerificationRule.OVER_ISSUE, 0);
        RuleOutcome broken = RuleOutcome.failed(VerificationRule.OVER_ISSUE, "Unknown column");

        // then
        assertThat(empty.checkedCount()).isEqualTo(broken.checkedCount());
        assertThat(empty.violationCount()).isEqualTo(broken.violationCount());
        assertThat(empty.status()).isNotEqualTo(broken.status());
    }

    @Test
    @DisplayName("실패한 규칙에 사유가 없으면 거부한다")
    void refusesFailureWithoutReason() {
        // when, then
        assertThatThrownBy(
                        () ->
                                new RuleOutcome(
                                        VerificationRule.OVER_ISSUE,
                                        RuleStatus.FAILED,
                                        0,
                                        0,
                                        List.of(),
                                        " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사유");
    }

    /** 대량 위반은 상한까지만 저장하므로 전체 건수와 표본 크기가 갈린다. */
    @Test
    @DisplayName("상한에 걸려 상세를 다 담지 못하면 잘렸다고 표시한다")
    void marksTruncatedWhenSampleIsSmallerThanTotal() {
        // when
        RuleOutcome outcome =
                RuleOutcome.violated(
                        VerificationRule.STATE_TIMESTAMP_MISMATCH, 3_000_000, 5_000, List.of(SAMPLE));

        // then
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.violationCount()).isEqualTo(5_000);
        assertThat(outcome.violations()).hasSize(1);
    }

    /** 집계 쿼리와 상세 쿼리가 다른 기준으로 만들어지면 여기서 드러난다. */
    @Test
    @DisplayName("표본이 전체 위반 수보다 많으면 거부한다")
    void refusesWhenSampleExceedsTotal() {
        // when, then
        assertThatThrownBy(
                        () -> RuleOutcome.violated(VerificationRule.OVER_ISSUE, 301, 0, List.of(SAMPLE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("표본");
    }

    @Test
    @DisplayName("검사 건수가 음수면 거부한다")
    void refusesNegativeCounts() {
        // when, then
        assertThatThrownBy(
                        () ->
                                new RuleOutcome(
                                        VerificationRule.OVER_ISSUE,
                                        RuleStatus.CHECKED,
                                        -1,
                                        0,
                                        List.of(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("넘겨받은 목록이 나중에 바뀌어도 결과는 영향받지 않는다")
    void keepsViolationsImmutable() {
        // given
        List<Violation> mutable = new ArrayList<>(List.of(SAMPLE));
        RuleOutcome outcome = RuleOutcome.violated(VerificationRule.OVER_ISSUE, 301, 1, mutable);

        // when
        mutable.clear();

        // then
        assertThat(outcome.violations()).hasSize(1);
    }
}
