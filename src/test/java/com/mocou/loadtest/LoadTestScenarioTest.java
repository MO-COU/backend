package com.mocou.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoadTestScenarioTest {

    @Test
    void definesScenarioLoadShapeWithoutCouponRound() {
        assertThat(LoadTestScenario.V1_RAMP_20000.vus()).isEqualTo(20_000);
        assertThat(LoadTestScenario.V1_RAMP_20000.rampUpSeconds()).isEqualTo(60);
        assertThat(LoadTestScenario.V2_SPIKE_20000.rampUpSeconds()).isZero();
        assertThat(LoadTestScenario.V3_SPIKE_50000.vus()).isEqualTo(50_000);
        assertThat(LoadTestScenario.V4_RAMP_ONCE_20000.vus()).isEqualTo(20_000);
        assertThat(LoadTestScenario.V4_RAMP_ONCE_20000.rampUpSeconds()).isEqualTo(60);
        assertThat(LoadTestScenario.V5_RATE_4000_RPS.expectedStock()).isEqualTo(10_000);
        assertThat(LoadTestScenario.V6_REPEAT_1_TO_3.vus()).isEqualTo(20_000);
    }
}
