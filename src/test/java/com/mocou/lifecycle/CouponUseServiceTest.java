package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.notification.NotificationSender;
import com.mocou.notification.NotificationType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponUseServiceTest {

    private static final long ISSUE_ID = 42L;
    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;
    private static final String IDEMPOTENCY_KEY = "use-request-1";
    private static final LocalDateTime USED_AT = LocalDateTime.of(2026, 8, 18, 15, 30);

    @Mock private CouponUseRepository repository;
    @Mock private NotificationSender notificationSender;
    @InjectMocks private CouponUseService service;

    @Test
    @DisplayName("발급 쿠폰을 사용 처리하고 이력을 저장한다")
    void usesIssuedCouponAndRecordsHistory() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty());
        given(repository.markUsed(ISSUE_ID)).willReturn(1);
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.USED,
                                        USED_AT,
                                        false)));

        // when
        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        // then
        assertThat(result)
                .isEqualTo(new CouponUseResult(ISSUE_ID, CouponIssueStatus.USED, USED_AT));
        verify(repository).saveUsedHistory(ISSUE_ID, IDEMPOTENCY_KEY);
        verify(notificationSender).notifyMember(NotificationType.USED, COUPON_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("같은 멱등성 키 요청은 기존 성공 결과를 반환한다")
    void returnsExistingSuccessForRepeatedKey() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.of(CouponIssueStatus.USED));
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.USED,
                                        USED_AT,
                                        false)));

        // when
        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        // then
        assertThat(result.usedAt()).isEqualTo(USED_AT);
        verify(repository, never()).markUsed(ISSUE_ID);
        verify(repository, never()).saveUsedHistory(ISSUE_ID, IDEMPOTENCY_KEY);
        verify(notificationSender, never()).notifyMember(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("다른 상태 전이에 사용된 멱등성 키는 충돌로 거부한다")
    void rejectsKeyPreviouslyUsedForAnotherTransition() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.of(CouponIssueStatus.EXPIRED));

        // when, then
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT);

        verify(repository, never()).markUsed(ISSUE_ID);
    }

    @Test
    @DisplayName("발급 쿠폰이 없으면 오류를 반환한다")
    void rejectsMissingIssue() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty());
        given(repository.markUsed(ISSUE_ID)).willReturn(0);
        given(repository.findIssue(ISSUE_ID)).willReturn(Optional.empty());

        // when, then
        assertError(ErrorCode.ISSUE_NOT_FOUND);
    }

    @Test
    @DisplayName("만료된 발급 쿠폰은 사용할 수 없다")
    void rejectsExpiredIssuedCoupon() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty());
        given(repository.markUsed(ISSUE_ID)).willReturn(0);
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.ISSUED,
                                        null,
                                        true)));

        // when, then
        assertError(ErrorCode.COUPON_EXPIRED);
    }

    @Test
    @DisplayName("이미 사용된 쿠폰은 다른 키로 전이할 수 없다")
    void rejectsTransitionFromTerminalStateWithAnotherKey() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty());
        given(repository.markUsed(ISSUE_ID)).willReturn(0);
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.USED,
                                        USED_AT,
                                        false)));

        // when, then
        assertError(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("만료 완료 상태는 만료 쿠폰 오류로 반환한다")
    void rejectsExpiredTerminalStateAsExpiredCoupon() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty());
        given(repository.markUsed(ISSUE_ID)).willReturn(0);
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.EXPIRED,
                                        null,
                                        true)));

        // when, then
        assertError(ErrorCode.COUPON_EXPIRED);
    }

    @Test
    @DisplayName("경쟁 요청이 먼저 성공하면 그 결과를 반환한다")
    void seesConcurrentSuccessBeforeResolvingFailedUpdate() {
        // given
        given(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(CouponIssueStatus.USED));
        given(repository.markUsed(ISSUE_ID)).willReturn(0);
        given(repository.findIssue(ISSUE_ID))
                .willReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID,
                                        COUPON_ID,
                                        MEMBER_ID,
                                        CouponIssueStatus.USED,
                                        USED_AT,
                                        false)));

        // when
        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        // then
        assertThat(result.usedAt()).isEqualTo(USED_AT);
    }

    @Test
    @DisplayName("0 이하 발급 ID는 입력 오류로 거부한다")
    void rejectsNonPositiveIssueId() {
        // when, then
        assertThatThrownBy(() -> service.use(0, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "                                                                x"})
    @DisplayName("유효하지 않은 멱등성 키는 입력 오류로 거부한다")
    void rejectsInvalidIdempotencyKey(String key) {
        // when, then
        assertThatThrownBy(() -> service.use(ISSUE_ID, key))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    private void assertError(ErrorCode expected) {
        assertThatThrownBy(() -> service.use(ISSUE_ID, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
