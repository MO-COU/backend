package com.mocou.admin;

import java.time.LocalDateTime;

public record AdminCouponStock(
        long couponId,
        String couponName,
        LocalDateTime openAt,
        int totalQuantity,
        int issuedQuantity,
        int dbIssuedQuantity,
        int syncGapQuantity,
        int remainingQuantity,
        String status,
        LocalDateTime updatedAt) {

    public AdminCouponStock(
            long couponId,
            String couponName,
            LocalDateTime openAt,
            int totalQuantity,
            int issuedQuantity,
            int remainingQuantity,
            String status,
            LocalDateTime updatedAt) {
        this(
                couponId,
                couponName,
                openAt,
                totalQuantity,
                issuedQuantity,
                issuedQuantity,
                0,
                remainingQuantity,
                status,
                updatedAt);
    }

    public AdminCouponStock withIssueProgress(
            int realtimeRemainingQuantity, int databaseIssuedQuantity) {
        if (realtimeRemainingQuantity < 0 || realtimeRemainingQuantity > totalQuantity) {
            throw new IllegalArgumentException("실시간 잔여 재고가 총 재고 범위를 벗어났습니다");
        }
        if (databaseIssuedQuantity < 0) {
            throw new IllegalArgumentException("DB 발급 건수는 음수일 수 없습니다");
        }
        int realtimeIssuedQuantity = totalQuantity - realtimeRemainingQuantity;
        return new AdminCouponStock(
                couponId,
                couponName,
                openAt,
                totalQuantity,
                realtimeIssuedQuantity,
                databaseIssuedQuantity,
                realtimeIssuedQuantity - databaseIssuedQuantity,
                realtimeRemainingQuantity,
                status,
                updatedAt);
    }
}
