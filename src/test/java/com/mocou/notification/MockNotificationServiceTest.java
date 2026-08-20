package com.mocou.notification;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MockNotificationServiceTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;

    @Mock private NotificationRepository repository;
    @InjectMocks private MockNotificationService service;

    @Test
    @DisplayName("회원 알림은 coupon/member id를 채워 SENT로 저장한다")
    void notifiesMemberAndSavesAsSent() {
        // when
        service.notifyMember(NotificationType.ISSUE_SUCCESS, COUPON_ID, MEMBER_ID);

        // then
        verify(repository)
                .save(
                        argThat(
                                record ->
                                        record.couponId().equals(COUPON_ID)
                                                && record.memberId().equals(MEMBER_ID)
                                                && record.type() == NotificationType.ISSUE_SUCCESS
                                                && record.status() == NotificationStatus.SENT
                                                && record.sentAt() != null));
    }

    @Test
    @DisplayName("관리자 알림은 member id 없이 저장한다")
    void notifiesAdminWithoutMemberId() {
        // when
        service.notifyAdmin(NotificationType.STOCK_DEPLETED, COUPON_ID);

        // then
        verify(repository)
                .save(
                        argThat(
                                record ->
                                        record.couponId().equals(COUPON_ID)
                                                && record.memberId() == null
                                                && record.type() == NotificationType.STOCK_DEPLETED
                                                && record.status() == NotificationStatus.SENT));
    }

    @Test
    @DisplayName("특정 쿠폰에 묶이지 않는 관리자 알림은 coupon id도 null로 저장한다")
    void notifiesAdminWithoutCouponId() {
        // when
        service.notifyAdmin(NotificationType.VERIFICATION_FAILED, null);

        // then
        verify(repository)
                .save(
                        argThat(
                                record ->
                                        record.couponId() == null
                                                && record.memberId() == null
                                                && record.type()
                                                        == NotificationType.VERIFICATION_FAILED));
    }
}
