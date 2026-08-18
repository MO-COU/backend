package com.mocou.lifecycle;

import java.time.LocalDateTime;

public record CouponExpirationCandidate(long couponIssueId, LocalDateTime expiresAt) {}
