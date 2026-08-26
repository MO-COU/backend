package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminCouponIssueResultCountsTest {

    @Test
    @DisplayName("DB 적재와 보상을 제외한 예약을 처리 중 또는 재시도 중으로 계산한다")
    void calculatesPendingOrRetryingCount() {
        AdminCouponIssueResultCounts counts =
                AdminCouponIssueResultCounts.of(10L, 10_000, 0, 0, 0, 0, 0, 0, 3)
                        .withPersistenceProgress(9_980);

        assertThat(counts.dbPersisted()).isEqualTo(9_980);
        assertThat(counts.pendingOrRetrying()).isEqualTo(17);
    }

    @Test
    @DisplayName("DB 적재와 보상 합계가 예약보다 크면 처리 중 건수를 0으로 표시한다")
    void clampsPendingOrRetryingCountToZero() {
        AdminCouponIssueResultCounts counts =
                AdminCouponIssueResultCounts.of(10L, 10, 0, 0, 0, 0, 0, 0, 3)
                        .withPersistenceProgress(12);

        assertThat(counts.pendingOrRetrying()).isZero();
    }
}
