package com.mocou.admin;

import java.time.LocalDateTime;

public record AdminCouponStock(
        long couponId,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        String status,
        LocalDateTime updatedAt) {}
