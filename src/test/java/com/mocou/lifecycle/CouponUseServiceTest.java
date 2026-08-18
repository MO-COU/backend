package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponUseServiceTest {

    private static final long ISSUE_ID = 42L;
    private static final String IDEMPOTENCY_KEY = "use-request-1";
    private static final LocalDateTime USED_AT = LocalDateTime.of(2026, 8, 18, 15, 30);

    @Mock private CouponUseRepository repository;

    private CouponUseService service;

    @BeforeEach
    void setUp() {
        service = new CouponUseService(repository);
    }

    @Test
    void usesIssuedCouponAndRecordsHistory() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(repository.markUsed(ISSUE_ID)).thenReturn(1);
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.USED, USED_AT, false)));

        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        assertThat(result)
                .isEqualTo(new CouponUseResult(ISSUE_ID, CouponIssueStatus.USED, USED_AT));
        verify(repository).saveUsedHistory(ISSUE_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void returnsExistingSuccessForRepeatedKey() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(CouponIssueStatus.USED));
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.USED, USED_AT, false)));

        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        assertThat(result.usedAt()).isEqualTo(USED_AT);
        verify(repository, never()).markUsed(ISSUE_ID);
        verify(repository, never()).saveUsedHistory(ISSUE_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void rejectsKeyPreviouslyUsedForAnotherTransition() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(CouponIssueStatus.EXPIRED));

        assertError(CouponUseErrorCode.IDEMPOTENCY_CONFLICT);

        verify(repository, never()).markUsed(ISSUE_ID);
    }

    @Test
    void rejectsMissingIssue() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(repository.markUsed(ISSUE_ID)).thenReturn(0);
        when(repository.findIssue(ISSUE_ID)).thenReturn(Optional.empty());

        assertError(CouponUseErrorCode.ISSUE_NOT_FOUND);
    }

    @Test
    void rejectsExpiredIssuedCoupon() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(repository.markUsed(ISSUE_ID)).thenReturn(0);
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.ISSUED, null, true)));

        assertError(CouponUseErrorCode.COUPON_EXPIRED);
    }

    @Test
    void rejectsTransitionFromTerminalStateWithAnotherKey() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(repository.markUsed(ISSUE_ID)).thenReturn(0);
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.USED, USED_AT, false)));

        assertError(CouponUseErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void rejectsExpiredTerminalStateAsExpiredCoupon() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(repository.markUsed(ISSUE_ID)).thenReturn(0);
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.EXPIRED, null, true)));

        assertError(CouponUseErrorCode.COUPON_EXPIRED);
    }

    @Test
    void seesConcurrentSuccessBeforeResolvingFailedUpdate() {
        when(repository.findHistoryTargetStatus(ISSUE_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(CouponIssueStatus.USED));
        when(repository.markUsed(ISSUE_ID)).thenReturn(0);
        when(repository.findIssue(ISSUE_ID))
                .thenReturn(
                        Optional.of(
                                new CouponIssueState(
                                        ISSUE_ID, CouponIssueStatus.USED, USED_AT, false)));

        CouponUseResult result = service.use(ISSUE_ID, IDEMPOTENCY_KEY);

        assertThat(result.usedAt()).isEqualTo(USED_AT);
    }

    @Test
    void rejectsNonPositiveIssueId() {
        assertThatThrownBy(() -> service.use(0, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(CouponUseErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "                                                                x"})
    void rejectsInvalidIdempotencyKey(String key) {
        assertThatThrownBy(() -> service.use(ISSUE_ID, key))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(CouponUseErrorCode.INVALID_INPUT));
    }

    private void assertError(CouponUseErrorCode expected) {
        assertThatThrownBy(() -> service.use(ISSUE_ID, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(
                        CouponUseException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
