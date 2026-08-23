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
    @DisplayName("위반이 없으면 통과로 판정한다")
    void passesWhenNoViolation() {
        // when
        RuleOutcome outcome = RuleOutcome.passed(VerificationRule.OVER_ISSUE, 301);

        // then
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    @DisplayName("위반이 한 건이라도 있으면 통과가 아니다")
    void failsWhenAnyViolation() {
        // when
        RuleOutcome outcome =
                new RuleOutcome(VerificationRule.OVER_ISSUE, 301, 1, List.of(SAMPLE));

        // then
        assertThat(outcome.passed()).isFalse();
    }

    /** 대량 위반은 상한까지만 저장하므로 전체 건수와 표본 크기가 갈린다. */
    @Test
    @DisplayName("상한에 걸려 상세를 다 담지 못하면 잘렸다고 표시한다")
    void marksTruncatedWhenSampleIsSmallerThanTotal() {
        // when
        RuleOutcome outcome =
                new RuleOutcome(VerificationRule.STATE_TIMESTAMP_MISMATCH, 3_000_000, 5_000, List.of(SAMPLE));

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
        assertThatThrownBy(() -> new RuleOutcome(VerificationRule.OVER_ISSUE, 301, 0, List.of(SAMPLE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("표본");
    }

    @Test
    @DisplayName("검사 건수가 음수면 거부한다")
    void refusesNegativeCounts() {
        // when, then
        assertThatThrownBy(() -> new RuleOutcome(VerificationRule.OVER_ISSUE, -1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("넘겨받은 목록이 나중에 바뀌어도 결과는 영향받지 않는다")
    void keepsViolationsImmutable() {
        // given
        List<Violation> mutable = new ArrayList<>(List.of(SAMPLE));
        RuleOutcome outcome = new RuleOutcome(VerificationRule.OVER_ISSUE, 301, 1, mutable);

        // when
        mutable.clear();

        // then
        assertThat(outcome.violations()).hasSize(1);
    }
}
