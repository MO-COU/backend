package com.mocou.lifecycle;

record CouponUsedEvent(long couponIssueId, long couponId, long memberId) {}
