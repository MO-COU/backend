package com.mocou.notification;

import java.util.List;

/**
 * A/B팀이 알림을 트리거할 때 호출하는 공개 인터페이스.
 * notification 패키지 내부 구현(엔티티, 레포지토리)에 직접 의존하지 말고 이것만 주입받아 쓴다.
 *
 * 예)
 *   A팀 발급 성공 시(건별):   notificationSender.notifyMember(NotificationType.ISSUE_SUCCESS, couponId, memberId);
 *   A팀 발급 성공 시(배치):   notificationSender.notifyMembers(NotificationType.ISSUE_SUCCESS, couponId, memberIds);
 *   B팀 재고 소진 감지 시:    notificationSender.notifyAdmin(NotificationType.STOCK_DEPLETED, couponId);
 */
public interface NotificationSender {

    /** 특정 회원에게 보내는 알림 (OPEN_SOON, ISSUE_SUCCESS, EXPIRE_SOON, USED). */
    void notifyMember(NotificationType type, long couponId, long memberId);

    /**
     * 관리자에게 보내는 알림 (STOCK_DEPLETED, VERIFICATION_FAILED).
     */
    void notifyAdmin(NotificationType type, Long couponId);

    /**
     * 같은 쿠폰에 대해 같은 종류의 알림을 여러 회원에게 한 번에 큐잉한다 — 쿠폰 발급
     * 배치(saveBatch)처럼 한 트랜잭션에서 자연스럽게 다건이 발생하는 곳 전용이다.
     * 큐잉(insert)은 건별로 이뤄지지만(중복 skip이 건별 유니크 제약에 걸려 있어서),
     * 발송 성공 후 상태 갱신(PENDING→SENT)은 이 호출 단위로 묶여 나간다.
     */
    void notifyMembers(NotificationType type, long couponId, List<Long> memberIds);
}
