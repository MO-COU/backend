package com.mocou.notification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * outbox: {@code notification} 테이블에 PENDING으로 쌓인 알림을 실제(모킹) 발송하고
 * 결과를 기록하는 전담 컴포넌트.
 *
 * <p>발송 시도는 두 경로로 들어온다: (1) {@link MockNotificationService}가 큐잉 트랜잭션이
 * 커밋된 직후 그 트랜잭션에서 나온 알림들을 묶어 이 인스턴스의 {@link #processBatch}를 바로
 * 호출하는 "즉시 경로"(대부분의 알림이 이 경로로 지연 없이 처리된다), (2) 이 클래스의
 * {@link #dispatch()}가 폴링으로 훑는 "안전망 경로" — 즉시 경로가 어떤 이유로든(예: 프로세스가
 * 커밋 직후 죽음, dispatch가 처음엔 꺼져 있었음) 못 미친 PENDING row를 나중에라도 건진다.
 * 그래서 폴링 간격을 짧게 잡을 필요가 없다.
 *
 * <p>발송에 성공한 건(대부분)만 {@link NotificationRepository#markSentBatch}로 묶어서 SENT
 * 처리한다 — 이게 DB 왕복이 잦은 hot path이기 때문이다. 재시도 카운트 증가/최종실패(FAILED)는
 * 건마다 사유가 달라 묶을 실익이 적고 드물게 발생해서 단건으로 처리한다.
 *
 * <p>단일 인스턴스 MVP라 별도 락/소유권 개념이 필요 없다 — row는 PENDING이거나 아니거나 둘
 * 중 하나다.
 *
 * <p>{@code mocou.notification.dispatch.enabled=true}일 때만 동작한다(기본 꺼짐) — 이 빈이
 * 없으면 즉시 경로도, 폴링도 전혀 일어나지 않고 알림은 PENDING인 채로 남는다(이 흐름과
 * 무관한 테스트까지 오염시키지 않기 위함).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mocou.notification.dispatch", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotificationDispatchConsumer {

    private final NotificationRepository notificationRepository;
    private final NotificationDispatchProperties properties;

    /** 안전망 경로 - 즉시 경로가 놓친 PENDING row를 늦게라도 훑어서 처리한다. */
    @Scheduled(fixedDelayString = "${mocou.notification.dispatch.poll-interval-ms:5000}")
    public void dispatch() {
        processBatch(notificationRepository.findPending(properties.getBatchSize()));
    }

    /**
     * 즉시 경로/안전망 경로 공용 처리 로직. package-private인 이유는
     * {@link MockNotificationService}가 직접 호출하기 때문.
     *
     * <p>건별로 send()는 개별 호출하지만(각자 다른 수신자에게 보내는 별개 메시지라 묶을 수
     * 없음), 성공한 것만 모아뒀다가 이 배치 전체에 대해 markSentBatch를 한 번만 부른다.
     */
    void processBatch(List<PendingNotification> notifications) {
        List<Long> sentIds = new ArrayList<>();
        for (PendingNotification notification : notifications) {
            if (send(notification)) {
                sentIds.add(notification.notificationId());
                continue;
            }

            if (notification.retryCount() + 1 > properties.getMaxDeliveryCount()) {
                // 재시도 한도 소진 - 별도 실패 로그 없이 status만 FAILED로 확정한다. 드물게
                // 발생하는 경로라 묶지 않고 건별로 처리해도 충분하다.
                notificationRepository.markFailed(notification.notificationId());
            } else {
                notificationRepository.incrementRetryCount(notification.notificationId());
            }
        }

        notificationRepository.markSentBatch(sentIds, LocalDateTime.now());
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
