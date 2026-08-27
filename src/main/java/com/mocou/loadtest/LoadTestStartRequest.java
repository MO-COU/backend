package com.mocou.loadtest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "관리자 부하 테스트 실행 요청")
public record LoadTestStartRequest(
        @Positive
                @Schema(description = "부하 테스트 대상 쿠폰 회차 ID", example = "301")
                long couponId,
        @NotNull
                @Schema(description = "실행할 k6 시나리오", example = "V1_RAMP_20000")
                LoadTestScenario scenario) {}
