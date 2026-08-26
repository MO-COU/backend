package com.mocou.notification;

import org.springframework.stereotype.Service;

/**
 * outbox: 실제 알림 서버를 직접 호출하지 않고, notification 테이블에 PENDING으로
 * 기록만 하고 즉시 반환한다. 호출부(A/B팀의 @Transactional 메서드) 안에서 호출되면
 * 이 insert가 그 트랜잭션에 그대로 합류하므로, 비즈니스 write와 알림 큐잉이 원자적으로
 * 묶인다(outbox 패턴).
 *
 * <p>실제 발송과 상태 갱신(SENT/FAILED)은 {@link NotificationDispatchConsumer}가 전담한다.
 */
@Service
public class MockNotificationService implements NotificationSender {

    private final NotificationRepository notificationRepository;

    public MockNotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void notifyMember(NotificationType type, long couponId, long memberId) {
        notificationRepository.save(
                new NotificationRecord(couponId, memberId, type, NotificationStatus.PENDING, null));
    }

    @Override
    public void notifyAdmin(NotificationType type, Long couponId) {
        notificationRepository.save(
                new NotificationRecord(couponId, null, type, NotificationStatus.PENDING, null));
    }
}
