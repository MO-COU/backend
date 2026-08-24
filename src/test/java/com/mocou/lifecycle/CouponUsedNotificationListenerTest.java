package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;

import com.mocou.notification.NotificationSender;
import com.mocou.notification.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponUsedNotificationListenerTest {

    private static final long ISSUE_ID = 42L;
    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;

    @Mock private NotificationSender notificationSender;
    @InjectMocks private CouponUsedNotificationListener listener;

    @Test
    @DisplayName("사용 알림 실패는 커밋된 쿠폰 사용 결과에 영향을 주지 않는다")
    void isolatesNotificationFailureFromCommittedCouponUse() {
        // given
        CouponUsedEvent event = new CouponUsedEvent(ISSUE_ID, COUPON_ID, MEMBER_ID);
        willThrow(new IllegalStateException("알림 저장 실패"))
                .given(notificationSender)
                .notifyMember(NotificationType.USED, COUPON_ID, MEMBER_ID);

        // when, then
        assertThatCode(() -> listener.notifyCouponUsed(event)).doesNotThrowAnyException();
    }
}
