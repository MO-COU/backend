package com.mocou.lifecycle;

import java.time.LocalDateTime;

public record CouponUseResult(
        long couponIssueId, CouponIssueStatus status, LocalDateTime usedAt) {}
