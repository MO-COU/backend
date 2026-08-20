package com.mocou.issue;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponIssueReservationRequest(
        @NotNull
        @Positive
        Long memberId
) {
}
