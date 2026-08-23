package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViolationTest {

    @Test
    @DisplayName("단일 대상 위반은 2차 식별자가 비어 있다")
    void singleTargetHasNoSecondIdentifier() {
        // when
        Violation violation = Violation.of(ViolationTarget.COUPON, 5L, "총재고 10000, 발급 10001");

        // then
        assertThat(violation.targetId2()).isNull();
    }

    @Test
    @DisplayName("쌍 위반은 두 식별자를 모두 갖는다")
    void pairTargetKeepsBothIdentifiers() {
        // when
        Violation violation =
                new Violation(ViolationTarget.COUPON_MEMBER_PAIR, 5L, 12345L, "발급 2건");

        // then
        assertThat(violation.targetId()).isEqualTo(5L);
        assertThat(violation.targetId2()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("상세 설명이 비어 있으면 거부한다")
    void refusesBlankDetail() {
        // when, then
        assertThatThrownBy(() -> Violation.of(ViolationTarget.COUPON, 5L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위반 상세");
    }

    /** 컬럼이 500자라 그냥 두면 적재 시점에 잘려 원인 조사에 필요한 수치가 사라진다. */
    @Test
    @DisplayName("상세 설명이 컬럼 길이를 넘으면 거부한다")
    void refusesDetailLongerThanColumn() {
        // given
        String tooLong = "가".repeat(501);

        // when, then
        assertThatThrownBy(() -> Violation.of(ViolationTarget.COUPON, 5L, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500자");
    }
}
