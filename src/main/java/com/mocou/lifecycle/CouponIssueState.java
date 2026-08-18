package com.mocou.lifecycle;

import java.time.LocalDateTime;

public record CouponIssueState(
        long couponIssueId, CouponIssueStatus status, LocalDateTime usedAt, boolean expired) {}
