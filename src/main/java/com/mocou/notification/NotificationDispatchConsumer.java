package com.mocou.notification;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * outbox: {@code notification} 테이블에 PENDING으로 쌓인 알림을 폴링해 실제(모킹) 발송하고
 * 결과를 기록하는 전담 컨슈머.
 *
 * <p>단일 인스턴스 MVP라 별도 락/소유권 개념이 필요 없다 — row는 PENDING이거나 아니거나 둘
 * 중 하나다. 처리에 실패하면 ACK 대신 재시도 횟수만 올려 다음 tick에 그대로 다시 집힌다.
 *
 * <p>결과는 반드시 둘 중 하나로 귀결된다: 성공하면 SENT로, 재시도 한도를 넘겨 최종 실패하면
 * FAILED로 남는다 — 별도 실패 로그 테이블 없이 status만으로 실패 여부를 표현한다.
 *
 * <p>{@code mocou.notification.dispatch.enabled=true}일 때만 동작한다(기본 꺼짐) — 이 빈이
 * 매 tick마다 DB를 폴링하는데, 이 흐름과 무관한 테스트까지 오염시키지 않기 위함.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mocou.notification.dispatch", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotificationDispatchConsumer {

    private final NotificationRepository notificationRepository;
    private final NotificationDispatchProperties properties;

    @Scheduled(fixedDelayString = "${mocou.notification.dispatch.poll-interval-ms:100}")
    public void dispatch() {
        List<PendingNotification> pending = notificationRepository.findPending(properties.getBatchSize());
        for (PendingNotification notification : pending) {
            processOne(notification);
        }
    }

    private void processOne(PendingNotification notification) {
        if (send(notification)) {
            notificationRepository.markSent(notification.notificationId(), LocalDateTime.now());
            return;
        }

        if (notification.retryCount() + 1 > properties.getMaxDeliveryCount()) {
            // 재시도 한도 소진 - 별도 실패 로그 없이 status만 FAILED로 확정한다.
            notificationRepository.markFailed(notification.notificationId());
            return;
        }

        notificationRepository.incrementRetryCount(notification.notificationId());
    }

    /**
     * @return 발송 성공 여부. 실패해도 예외를 밖으로 던지지 않는다 - 재시도는 호출부가 결정한다.
     *     package-private로 열어둔 건 테스트가 통신 실패를 재현할 수 있는 유일한 seam이기
     *     때문이다 - 지금은 로그만 남기는 모킹이라 여기서 절대 실패하지 않는다.
     */
    boolean send(PendingNotification notification) {
        try {
            log.info(
                    "[NotificationDispatch] type={}, couponId={}, memberId={}",
                    notification.type(), notification.couponId(), notification.memberId());
            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "알림 발송에 실패했습니다. 재시도 대상으로 남깁니다. type={}, couponId={}, memberId={}",
                    notification.type(), notification.couponId(), notification.memberId(), exception);
            return false;
        }
    }
}
