package com.mocou.notification;

import java.time.LocalDateTime;

/** couponId/memberId는 관리자 알림일 경우 null일 수 있다 (V3 마이그레이션으로 nullable 전환됨). */
public record NotificationRecord(
        Long couponId,
        Long memberId,
        NotificationType type,
        NotificationStatus status,
        LocalDateTime sentAt) {}
