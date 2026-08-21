package com.mocou.admin;

import java.time.LocalDateTime;

public record AdminCouponStock(
        long couponId,
        String couponName,
        LocalDateTime openAt,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        String status,
        LocalDateTime updatedAt) {}
