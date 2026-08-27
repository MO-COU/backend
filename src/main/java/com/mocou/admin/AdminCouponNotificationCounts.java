package com.mocou.admin;

import com.mocou.notification.NotificationStatusCounts;

public record AdminCouponNotificationCounts(
        long couponId, long totalCount, long sentCount, long pendingCount, long failedCount) {

    public static AdminCouponNotificationCounts of(
            long couponId, NotificationStatusCounts counts) {
        return new AdminCouponNotificationCounts(
                couponId, counts.total(), counts.sent(), counts.pending(), counts.failed());
    }
}
