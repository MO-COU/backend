package com.mocou.issue;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "쿠폰 발급 예약 요청")
public record CouponIssueReservationRequest(
        @Schema(
                description = "쿠폰 발급을 요청하는 회원 ID",
                example = "1001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Long memberId
) {
}
