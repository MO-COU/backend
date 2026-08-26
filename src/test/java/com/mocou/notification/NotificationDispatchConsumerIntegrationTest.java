package com.mocou.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * outbox: NotificationDispatchConsumer가 PENDING row를 읽어 SENT/재시도/FAILED로 정확히
 * 옮기는지 검증한다. 실제(모킹) 발송 호출은 {@link #send}를 오버라이드해 통신 성공/실패를
 * 결정적으로 재현한다 - 기본 구현은 로그만 남기고 항상 성공하므로 실패 시나리오는 이 seam
 * 없이는 재현할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchConsumerIntegrationTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;

    @Mock private NotificationRepository notificationRepository;

    private boolean sendSucceeds = true;

    @BeforeEach
    void setUp() {
        sendSucceeds = true;
    }

    @Test
    @DisplayName("PENDING 알림을 발송에 성공하면 SENT로 표시한다")
    void marksSentOnSuccessfulDispatch() {
        // given
        given(notificationRepository.findPending(anyBatchSize()))
                .willReturn(List.of(pending(1L, 0)));
        NotificationDispatchConsumer consumer = consumer(properties(5));

        // when
        consumer.dispatch();

        // then
        verify(notificationRepository).markSent(eq(1L), any(LocalDateTime.class));
        verify(notificationRepository, never()).incrementRetryCount(1L);
        verify(notificationRepository, never()).markFailed(1L);
    }

    @Test
    @DisplayName("대기 중인 알림이 없으면 아무 것도 하지 않는다")
    void doesNothingWhenNothingPending() {
        // given
        given(notificationRepository.findPending(anyBatchSize())).willReturn(List.of());
        NotificationDispatchConsumer consumer = consumer(properties(5));

        // when
        consumer.dispatch();

        // then
        verify(notificationRepository, never()).markSent(anyLong(), any());
    }

    @Test
    @DisplayName("발송이 실패하고 재시도 한도 안쪽이면 재시도 횟수만 올린다")
    void incrementsRetryCountOnRetryableFailure() {
        // given
        sendSucceeds = false;
        given(notificationRepository.findPending(anyBatchSize()))
                .willReturn(List.of(pending(1L, 1)));
        NotificationDispatchConsumer consumer = consumer(properties(5));

        // when
        consumer.dispatch();

        // then
        verify(notificationRepository).incrementRetryCount(1L);
        verify(notificationRepository, never()).markSent(anyLong(), any());
        verify(notificationRepository, never()).markFailed(1L);
    }

    @Test
    @DisplayName("재시도 한도를 초과하면 별도 로그 없이 status만 FAILED로 확정한다")
    void marksFailedWhenRetryLimitExceeded() {
        // given
        sendSucceeds = false;
        given(notificationRepository.findPending(anyBatchSize()))
                .willReturn(List.of(pending(1L, 3)));
        NotificationDispatchConsumer consumer = consumer(properties(3));

        // when
        consumer.dispatch();

        // then
        verify(notificationRepository).markFailed(1L);
        verify(notificationRepository, never()).incrementRetryCount(1L);
    }

    private NotificationDispatchConsumer consumer(NotificationDispatchProperties properties) {
        return new NotificationDispatchConsumer(notificationRepository, properties) {
            @Override
            boolean send(PendingNotification notification) {
                return sendSucceeds;
            }
        };
    }

    private PendingNotification pending(long notificationId, int retryCount) {
        return new PendingNotification(
                notificationId, COUPON_ID, MEMBER_ID, NotificationType.ISSUE_SUCCESS, retryCount);
    }

    private NotificationDispatchProperties properties(int maxDeliveryCount) {
        NotificationDispatchProperties properties = new NotificationDispatchProperties();
        properties.setMaxDeliveryCount(maxDeliveryCount);
        return properties;
    }

    private int anyBatchSize() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
