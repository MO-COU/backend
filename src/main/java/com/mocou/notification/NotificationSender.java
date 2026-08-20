package com.mocou.notification;

/**
 * A/B팀이 알림을 트리거할 때 호출하는 공개 인터페이스.
 * notification 패키지 내부 구현(엔티티, 레포지토리)에 직접 의존하지 말고 이것만 주입받아 쓴다.
 *
 * 예)
 *   A팀 발급 성공 시:      notificationSender.notifyMember(NotificationType.ISSUE_SUCCESS, couponId, memberId);
 *   B팀 재고 소진 감지 시:  notificationSender.notifyAdmin(NotificationType.STOCK_DEPLETED, couponId);
 */
public interface NotificationSender {

    /** 특정 회원에게 보내는 알림 (OPEN_SOON, ISSUE_SUCCESS, EXPIRE_SOON, USED). */
    void notifyMember(NotificationType type, long couponId, long memberId);

    /**
     * 관리자에게 보내는 알림 (STOCK_DEPLETED, VERIFICATION_FAILED).
     */
    void notifyAdmin(NotificationType type, Long couponId);
}
