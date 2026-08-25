package com.mocou.lifecycle;

import com.mocou.notification.NotificationSender;
import com.mocou.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CouponUsedNotificationListener {

    private static final Logger log =
            LoggerFactory.getLogger(CouponUsedNotificationListener.class);

    private final NotificationSender notificationSender;

    public CouponUsedNotificationListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    /** 쿠폰 사용 트랜잭션이 커밋된 뒤 별도 트랜잭션으로 알림을 기록한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyCouponUsed(CouponUsedEvent event) {
        try {
            notificationSender.notifyMember(
                    NotificationType.USED, event.couponId(), event.memberId());
        } catch (RuntimeException exception) {
            // 알림 실패로 이미 커밋된 쿠폰 사용 결과가 실패 응답으로 바뀌지 않게 격리한다.
            log.error(
                    "쿠폰 사용 알림 처리에 실패했습니다. couponIssueId={}, couponId={}",
                    event.couponIssueId(),
                    event.couponId(),
                    exception);
        }
    }
}
