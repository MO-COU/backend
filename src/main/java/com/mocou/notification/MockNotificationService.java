package com.mocou.notification;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * outbox: 실제 알림 서버를 직접 호출하지 않고, notification 테이블에 PENDING으로
 * 기록만 하고 즉시 반환한다. 호출부(A/B팀의 @Transactional 메서드) 안에서 호출되면
 * 이 insert가 그 트랜잭션에 그대로 합류하므로, 비즈니스 write와 알림 큐잉이 원자적으로
 * 묶인다(outbox 패턴).
 *
 * <p>큐잉 트랜잭션이 커밋된 직후, 같은 호출에서 나온 알림들을 묶어 바로
 * {@link NotificationDispatchConsumer}에게 넘겨 즉시 발송을 시도한다 — 그래야 대부분의
 * 알림이 다음 폴링 tick(안전망, 기본 5초)까지 기다리지 않고 지연 없이 나간다.
 * {@code mocou.notification.dispatch.enabled=false}라 디스패처 빈이 없으면 즉시 시도
 * 자체를 생략하고 PENDING으로만 남긴다.
 */
@Service
public class MockNotificationService implements NotificationSender {

    private final NotificationRepository notificationRepository;
    private final ObjectProvider<NotificationDispatchConsumer> dispatchConsumer;

    public MockNotificationService(
            NotificationRepository notificationRepository,
            ObjectProvider<NotificationDispatchConsumer> dispatchConsumer) {
        this.notificationRepository = notificationRepository;
        this.dispatchConsumer = dispatchConsumer;
    }

    @Override
    public void notifyMember(NotificationType type, long couponId, long memberId) {
        notifyMembers(type, couponId, List.of(memberId));
    }

    @Override
    public void notifyAdmin(NotificationType type, Long couponId) {
        queueAndDispatch(List.of(new NotificationRecord(couponId, null, type, NotificationStatus.PENDING, null)));
    }

    @Override
    public void notifyMembers(NotificationType type, long couponId, List<Long> memberIds) {
        List<NotificationRecord> records = memberIds.stream()
                .map(memberId -> new NotificationRecord(couponId, memberId, type, NotificationStatus.PENDING, null))
                .toList();
        queueAndDispatch(records);
    }

    private void queueAndDispatch(List<NotificationRecord> records) {
        List<PendingNotification> queued = new ArrayList<>();
        for (NotificationRecord record : records) {
            Long notificationId = notificationRepository.save(record);
            if (notificationId == null) {
                // uk_notification_target 중복 - 이미 큐잉된 적 있으니 이 건은 새로 발송을 시도할 대상이 아니다.
                continue;
            }
            queued.add(new PendingNotification(
                    notificationId, record.couponId(), record.memberId(), record.type(), 0));
        }
        if (queued.isEmpty()) {
            return;
        }

        NotificationDispatchConsumer consumer = dispatchConsumer.getIfAvailable();
        if (consumer == null) {
            return;
        }

        dispatchAfterCommit(consumer, queued);
    }

    /*
     * 커밋 전에 발송을 시도하면, 발송(성공 로그/상태 갱신)까지 끝났는데 큐잉 자체가
     * 롤백되는 모순이 생긴다. 그래서 반드시 커밋 이후로 미룬다. 활성 트랜잭션이 없는
     * 호출(현재 실제 호출부는 전부 @Transactional 안이라 발생하지 않지만, 방어적으로)은
     * registerSynchronization이 예외를 던지므로 그 자리에서 바로 시도한다.
     */
    private void dispatchAfterCommit(NotificationDispatchConsumer consumer, List<PendingNotification> queued) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            consumer.processBatch(queued);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        consumer.processBatch(queued);
                    }
                });
    }
}
