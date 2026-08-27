package com.mocou.notification;

/** outbox: {@link NotificationRepository#findPending}이 돌려주는 발송 대기 항목. */
public record PendingNotification(
        long notificationId, Long couponId, Long memberId, NotificationType type, int retryCount) {}
