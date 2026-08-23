package com.mocou.issue;

import java.util.UUID;

public record CouponIssueReservationResult(
        UUID eventId,
        long couponId,
        long memberId,
        CouponIssueReservationStatus status
) {
}
