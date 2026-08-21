package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponIssueStatusTest {

    @Test
    @DisplayName("최초 발급 이력의 이전 상태를 UNISSUED로 해석한다")
    void parsesUnissuedStatus() {
        assertThat(CouponIssueStatus.valueOf("UNISSUED"))
                .isEqualTo(CouponIssueStatus.UNISSUED);
    }
}
