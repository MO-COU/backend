package com.mocou.admin;

import java.time.LocalDateTime;

public record AdminCouponIssue(
        long issueId,
        long couponId,
        long memberId,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt,
        LocalDateTime expiresAt) {}
