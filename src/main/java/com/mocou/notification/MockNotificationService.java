package com.mocou.notification;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 실제 외부 알림 API를 호출하지 않고, 로그 + notification 테이블 기록으로 대체함.
 * 항상 즉시 SENT로 처리한다 - Mock이므로 실패 시나리오는 없음.
 */
@Service
public class MockNotificationService implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationService.class);

    private final NotificationRepository notificationRepository;

    // Mock 처리
    public MockNotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void notifyMember(NotificationType type, long couponId, long memberId) {
        send(type, couponId, memberId);
    }

    @Override
    public void notifyAdmin(NotificationType type, Long couponId) {
        send(type, couponId, null);
    }

    // 발송 없이 로그 남기기
    private void send(NotificationType type, Long couponId, Long memberId) {
        LocalDateTime sentAt = LocalDateTime.now();
        log.info(
                "[MockNotification] type={}, couponId={}, memberId={}, sentAt={}",
                type,
                couponId,
                memberId,
                sentAt);
        notificationRepository.save(
                new NotificationRecord(couponId, memberId, type, NotificationStatus.SENT, sentAt));
    }
}
