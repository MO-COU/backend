package com.mocou.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

// outbox: MockNotificationService는 실제로 보내지 않고 notification 테이블에 PENDING으로
// 큐잉만 한다 - 실제 발송/SENT 기록은 NotificationDispatchConsumer가 담당(즉시 경로 포함).
@ExtendWith(MockitoExtension.class)
class MockNotificationServiceTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;

    @Mock private NotificationRepository notificationRepository;
    @Mock private ObjectProvider<NotificationDispatchConsumer> dispatchConsumerProvider;
    @Mock private NotificationDispatchConsumer dispatchConsumer;
    @InjectMocks private MockNotificationService service;

    // 회원 대상 알림(notifyMember/notifyMembers)은 saveBatch(배치 우선 시도 + 건별 폴백)를
    // 거친다 - save() 건별 호출은 notifyAdmin 전용이다.
    @Test
    @DisplayName("회원 알림은 coupon/member id로 saveBatch를 호출한다")
    void notifiesMemberByQueuingAsPending() {
        // when
        service.notifyMember(NotificationType.ISSUE_SUCCESS, COUPON_ID, MEMBER_ID);

        // then
        verify(notificationRepository)
                .saveBatch(COUPON_ID, NotificationType.ISSUE_SUCCESS, List.of(MEMBER_ID));
    }

    @Test
    @DisplayName("관리자 알림은 member id 없이 PENDING으로 큐잉한다")
    void notifiesAdminWithoutMemberId() {
        // when
        service.notifyAdmin(NotificationType.STOCK_DEPLETED, COUPON_ID);

        // then
        verify(notificationRepository)
                .save(
                        argThat(
                                record ->
                                        record.couponId().equals(COUPON_ID)
                                                && record.memberId() == null
                                                && record.type() == NotificationType.STOCK_DEPLETED
                                                && record.status() == NotificationStatus.PENDING));
    }

    @Test
    @DisplayName("특정 쿠폰에 묶이지 않는 관리자 알림은 coupon id도 null로 큐잉한다")
    void notifiesAdminWithoutCouponId() {
        // when
        service.notifyAdmin(NotificationType.VERIFICATION_FAILED, null);

        // then
        verify(notificationRepository)
                .save(
                        argThat(
                                record ->
                                        record.couponId() == null
                                                && record.memberId() == null
                                                && record.type()
                                                        == NotificationType.VERIFICATION_FAILED));
    }

    // outbox: 활성 트랜잭션 밖에서 호출되면(테스트가 그 경우) registerSynchronization을 못 쓰므로
    // 그 자리에서 바로 즉시 발송을 시도해야 한다 - 폴링(안전망, 기본 5초)까지 기다리면 안 된다.
    @Test
    @DisplayName("큐잉에 성공하고 디스패처가 떠 있으면 즉시 발송을 시도한다")
    void triggersImmediateDispatchWhenConsumerAvailable() {
        // given
        given(notificationRepository.saveBatch(eq(COUPON_ID), eq(NotificationType.ISSUE_SUCCESS), any()))
                .willReturn(List.of(
                        new PendingNotification(42L, COUPON_ID, MEMBER_ID, NotificationType.ISSUE_SUCCESS, 0)));
        given(dispatchConsumerProvider.getIfAvailable()).willReturn(dispatchConsumer);

        // when
        service.notifyMember(NotificationType.ISSUE_SUCCESS, COUPON_ID, MEMBER_ID);

        // then
        verify(dispatchConsumer)
                .processBatch(
                        argThat(
                                (List<PendingNotification> batch) ->
                                        batch.size() == 1
                                                && batch.getFirst().notificationId() == 42L
                                                && batch.getFirst().couponId().equals(COUPON_ID)
                                                && batch.getFirst().memberId().equals(MEMBER_ID)
                                                && batch.getFirst().type() == NotificationType.ISSUE_SUCCESS
                                                && batch.getFirst().retryCount() == 0));
    }

    // outbox: saveBatch처럼 한 번에 여러 회원에게 같은 알림을 큐잉하는 곳 전용 - 큐잉은
    // 배치 한 번이지만(또는 폴백 시 건별) 즉시 발송 시도는 이 호출에서 나온 건들을 묶어
    // 한 번에 넘어가야 한다.
    @Test
    @DisplayName("여러 회원에게 큐잉하면 즉시 발송도 그 건들을 한 번에 묶어 넘긴다")
    void triggersImmediateDispatchAsSingleBatchForMultipleMembers() {
        // given
        long memberId2 = 1002L;
        given(notificationRepository.saveBatch(
                        eq(COUPON_ID), eq(NotificationType.ISSUE_SUCCESS), eq(List.of(MEMBER_ID, memberId2))))
                .willReturn(List.of(
                        new PendingNotification(42L, COUPON_ID, MEMBER_ID, NotificationType.ISSUE_SUCCESS, 0),
                        new PendingNotification(43L, COUPON_ID, memberId2, NotificationType.ISSUE_SUCCESS, 0)));
        given(dispatchConsumerProvider.getIfAvailable()).willReturn(dispatchConsumer);

        // when
        service.notifyMembers(NotificationType.ISSUE_SUCCESS, COUPON_ID, List.of(MEMBER_ID, memberId2));

        // then
        verify(dispatchConsumer)
                .processBatch(
                        argThat(
                                (List<PendingNotification> batch) ->
                                        batch.size() == 2
                                                && batch.get(0).notificationId() == 42L
                                                && batch.get(1).notificationId() == 43L));
    }

    @Test
    @DisplayName("디스패처가 꺼져 있으면 즉시 발송을 시도하지 않고 PENDING으로만 남긴다")
    void skipsImmediateDispatchWhenDispatcherDisabled() {
        // given
        given(notificationRepository.saveBatch(eq(COUPON_ID), eq(NotificationType.ISSUE_SUCCESS), any()))
                .willReturn(List.of(
                        new PendingNotification(42L, COUPON_ID, MEMBER_ID, NotificationType.ISSUE_SUCCESS, 0)));
        given(dispatchConsumerProvider.getIfAvailable()).willReturn(null);

        // when, then (예외 없이 조용히 끝나야 한다)
        service.notifyMember(NotificationType.ISSUE_SUCCESS, COUPON_ID, MEMBER_ID);
        verify(dispatchConsumer, never()).processBatch(any());
    }

    @Test
    @DisplayName("중복이라 큐잉 자체가 skip되면 즉시 발송도 시도하지 않는다")
    void skipsImmediateDispatchWhenSaveIsDuplicate() {
        // given
        given(notificationRepository.saveBatch(eq(COUPON_ID), eq(NotificationType.ISSUE_SUCCESS), any()))
                .willReturn(List.of());

        // when
        service.notifyMember(NotificationType.ISSUE_SUCCESS, COUPON_ID, MEMBER_ID);

        // then
        verify(dispatchConsumerProvider, never()).getIfAvailable();
    }
}
