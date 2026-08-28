package com.mocou.notification;

// 알림 종류
public enum NotificationType {

    // 회원 대상
    OPEN_SOON,      // 오픈 10분 전 알림
    ISSUE_SUCCESS,  // 성공적으로 쿠폰 발급 알림
    // notification-stream: 재시도 소진으로 발급이 최종 실패했을 때 회원에게 보내는 알림
    ISSUE_FAILED,
    EXPIRE_SOON,    // 만료일 기준 하루 전 알림
    USED,           // 쿠폰 사용 알림

    // 관리자 대상 (member_id 없이 발송됨)
    STOCK_DEPLETED,         // 재고 0에 도달
    VERIFICATION_FAILED,    // 정합성 검증 실패 발생
    ISSUE_SYNC_FAILED       // DLQ 재시도까지 소진해 발급 동기화가 최종 실패했을 때 관리자에게 보내는 알림
}
