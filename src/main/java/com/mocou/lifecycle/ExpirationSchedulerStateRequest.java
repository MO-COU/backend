package com.mocou.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 만료 스케줄러의 다음 자동 실행 상태를 지정한다. */
@Schema(description = "만료 스케줄러 자동 실행 상태 변경 요청")
public record ExpirationSchedulerStateRequest(
        @Schema(
                        description = "다음 스케줄부터 만료 처리 배치를 자동 실행할지 여부",
                        example = "true",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Boolean enabled) {}
