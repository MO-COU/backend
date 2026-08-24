package com.mocou.lifecycle;

import java.time.LocalDateTime;

public record CouponIssueState(
        long couponIssueId,
        long couponId,
        long memberId,
        CouponIssueStatus status,
        LocalDateTime usedAt,
        boolean expired) {}
