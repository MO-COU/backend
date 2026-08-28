package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.sync.CouponIssueSyncRepository;
import com.mocou.notification.NotificationRepository;
import com.mocou.notification.NotificationStatusCounts;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

@ExtendWith(MockitoExtension.class)
class AdminCouponServiceTest {

    private static final long COUPON_ID = 10L;

    @Mock private AdminCouponRepository repository;
    @Mock private AdminCouponRealtimeStockRepository realtimeStockRepository;
    @Mock private RedisAdminCouponIssueResultRepository issueResultRepository;
    @Mock private RedisAdminCouponDlqFailureRepository dlqFailureRepository;
    @Mock private CouponIssueSyncRepository issueSyncRepository;
    @Mock private NotificationRepository notificationRepository;
    @InjectMocks private AdminCouponService service;

    @Test
    @DisplayName("존재하는 쿠폰의 알림 처리 현황을 조회한다")
    void returnsNotificationCounts() {
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(notificationRepository.countIssueSuccessByCouponId(COUPON_ID))
                .willReturn(new NotificationStatusCounts(10_000, 9_900, 90, 10));

        AdminCouponNotificationCounts result = service.getNotificationCounts(COUPON_ID);

        assertThat(result)
                .isEqualTo(new AdminCouponNotificationCounts(COUPON_ID, 10_000, 9_900, 90, 10));
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰의 알림 처리 현황 조회를 거부한다")
    void rejectsNotificationCountsForMissingCoupon() {
        given(repository.existsCoupon(COUPON_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getNotificationCounts(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.COUPON_NOT_FOUND));
        verify(notificationRepository, never()).countIssueSuccessByCouponId(COUPON_ID);
    }

    @Test
    @DisplayName("존재하는 쿠폰의 실시간 발급 결과를 조회한다")
    void returnsRealtimeIssueResultCounts() {
        // given
        AdminCouponIssueResultCounts counts =
                AdminCouponIssueResultCounts.of(COUPON_ID, 8_320, 1_200, 420, 30, 30, 0, 0, 20);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(repository.countIssues(COUPON_ID)).willReturn(7_980L);
        given(issueResultRepository.findCounts(COUPON_ID)).willReturn(counts);

        // when
        AdminCouponIssueResultCounts result = service.getIssueResultCounts(COUPON_ID);

        // then
        assertThat(result.dbPersisted()).isEqualTo(7_980);
        assertThat(result.pendingOrRetrying()).isEqualTo(320);
    }

    @Test
    @DisplayName("쿠폰 발급 이력을 페이지 단위로 조회한다")
    void returnsCouponIssuesByPage() {
        // given
        AdminCouponIssue issue =
                new AdminCouponIssue(
                        30L,
                        COUPON_ID,
                        100L,
                        "홍*동",
                        "ho*****@example.com",
                        "010-****-5678",
                        "ISSUED",
                        LocalDateTime.of(2026, 8, 19, 10, 0),
                        null,
                        LocalDateTime.of(2026, 8, 26, 10, 0),
                        5L,
                        95L);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(repository.countIssues(COUPON_ID)).willReturn(21L);
        given(repository.findIssues(COUPON_ID, 20, 0L)).willReturn(List.of(issue));

        // when
        AdminCouponIssuePage result = service.getIssues(COUPON_ID, 0, 20);

        // then
        assertThat(result.content()).containsExactly(issue);
        assertThat(result.totalElements()).isEqualTo(21L);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    @DisplayName("Redis가 초기화되지 않았으면 DB에 반영된 쿠폰 재고를 조회한다")
    void returnsCouponStock() {
        // given
        AdminCouponStock stock =
                new AdminCouponStock(
                        COUPON_ID,
                        "8월 3주차 선착순 쿠폰",
                        LocalDateTime.of(2026, 8, 17, 10, 0),
                        10_000,
                        8_000,
                        2_000,
                        "OPEN",
                        LocalDateTime.of(2026, 8, 19, 15, 0));
        given(repository.findStock(COUPON_ID)).willReturn(Optional.of(stock));
        given(repository.countIssues(COUPON_ID)).willReturn(8_000L);

        // when
        AdminCouponStock result = service.getStock(COUPON_ID);

        // then
        assertThat(result).isEqualTo(stock);
    }

    @Test
    @DisplayName("Redis가 초기화됐으면 실시간 잔여 재고를 우선 조회한다")
    void returnsRealtimeCouponStock() {
        // given
        AdminCouponStock databaseStock =
                new AdminCouponStock(
                        COUPON_ID,
                        "8월 3주차 선착순 쿠폰",
                        LocalDateTime.of(2026, 8, 17, 10, 0),
                        10_000,
                        8_000,
                        2_000,
                        "OPEN",
                        LocalDateTime.of(2026, 8, 19, 15, 0));
        given(repository.findStock(COUPON_ID)).willReturn(Optional.of(databaseStock));
        given(repository.countIssues(COUPON_ID)).willReturn(7_990L);
        given(realtimeStockRepository.findRemainingQuantity(COUPON_ID))
                .willReturn(java.util.OptionalInt.of(1_990));

        // when
        AdminCouponStock result = service.getStock(COUPON_ID);

        // then
        assertThat(result.totalQuantity()).isEqualTo(10_000);
        assertThat(result.issuedQuantity()).isEqualTo(8_010);
        assertThat(result.dbIssuedQuantity()).isEqualTo(7_990);
        assertThat(result.syncGapQuantity()).isEqualTo(20);
        assertThat(result.remainingQuantity()).isEqualTo(1_990);
    }

    @Test
    @DisplayName("Redis 잔여 재고가 총 재고보다 크면 조회를 거부한다")
    void rejectsInconsistentRealtimeCouponStock() {
        AdminCouponStock databaseStock =
                new AdminCouponStock(
                        COUPON_ID,
                        "8월 3주차 선착순 쿠폰",
                        LocalDateTime.of(2026, 8, 17, 10, 0),
                        10_000,
                        0,
                        10_000,
                        "OPEN",
                        LocalDateTime.of(2026, 8, 19, 15, 0));
        given(repository.findStock(COUPON_ID)).willReturn(Optional.of(databaseStock));
        given(repository.countIssues(COUPON_ID)).willReturn(0L);
        given(realtimeStockRepository.findRemainingQuantity(COUPON_ID))
                .willReturn(java.util.OptionalInt.of(10_001));

        assertThatThrownBy(() -> service.getStock(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰의 발급 이력 조회를 거부한다")
    void rejectsMissingCoupon() {
        // given
        given(repository.existsCoupon(COUPON_ID)).willReturn(false);

        // when, then
        assertThatThrownBy(() -> service.getIssues(COUPON_ID, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.COUPON_NOT_FOUND));
        verify(repository, never()).countIssues(COUPON_ID);
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰의 재고 조회를 거부한다")
    void rejectsMissingCouponStock() {
        given(repository.findStock(COUPON_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStock(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.COUPON_NOT_FOUND));
    }

    @Test
    @DisplayName("유효하지 않은 페이지 요청을 거부한다")
    void rejectsInvalidPageRequest() {
        assertThatThrownBy(() -> service.getIssues(COUPON_ID, -1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).existsCoupon(COUPON_ID);
    }

    @Test
    @DisplayName("회차 목록을 저장소가 준 순서 그대로 돌려준다")
    void returnsCouponSummaries() {
        // given - 최근 회차가 먼저 오도록 정렬하는 책임은 저장소에 있다
        AdminCouponSummary latest =
                new AdminCouponSummary(
                        302L,
                        "아메리카노 무료 쿠폰 302회차",
                        LocalDateTime.of(2026, 8, 26, 10, 0),
                        LocalDateTime.of(2026, 8, 26, 23, 59, 59),
                        10_000,
                        "OPEN");
        AdminCouponSummary previous =
                new AdminCouponSummary(
                        301L,
                        "아메리카노 무료 쿠폰 301회차",
                        LocalDateTime.of(2026, 8, 25, 10, 0),
                        LocalDateTime.of(2026, 8, 25, 23, 59, 59),
                        10_000,
                        "CLOSED");
        given(repository.findAllSummaries()).willReturn(List.of(latest, previous));

        // when & then
        assertThat(service.getCoupons()).containsExactly(latest, previous);
    }

    @Test
    @DisplayName("회차가 하나도 없으면 빈 목록을 돌려준다")
    void returnsEmptyListWhenNoCouponExists() {
        given(repository.findAllSummaries()).willReturn(List.of());

        assertThat(service.getCoupons()).isEmpty();
    }

    @Test
    @DisplayName("DLQ 실패 목록을 issue_failure_log 기록으로 보강해 돌려준다")
    void returnsDlqFailuresEnrichedWithFailureLog() {
        // given
        AdminCouponDlqFailure redisOnly =
                new AdminCouponDlqFailure(
                        "1735000000000-0", COUPON_ID, 100L, "event-1", 1L, 99L,
                        LocalDateTime.of(2026, 8, 26, 10, 0), null, null);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(dlqFailureRepository.findFailures(COUPON_ID)).willReturn(List.of(redisOnly));
        given(repository.findDlqFailureLogs(COUPON_ID))
                .willReturn(List.of(new AdminCouponFailureLogEntry(
                        100L, "INTERNAL_ERROR", LocalDateTime.of(2026, 8, 26, 10, 5))));

        // when
        List<AdminCouponDlqFailure> result = service.getDlqFailures(COUPON_ID);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().failureReason()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getFirst().occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 26, 10, 5));
    }

    @Test
    @DisplayName("issue_failure_log 기록이 없어도 Redis 실패 목록은 그대로 보여준다")
    void returnsDlqFailuresWithoutFailureLogWhenDbWriteMissed() {
        // given - DLQ 재시도가 소진된 원인이 DB 장애라면 fail log 자체가 없을 수 있다
        AdminCouponDlqFailure redisOnly =
                new AdminCouponDlqFailure(
                        "1735000000000-0", COUPON_ID, 100L, "event-1", 1L, 99L,
                        LocalDateTime.of(2026, 8, 26, 10, 0), null, null);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(dlqFailureRepository.findFailures(COUPON_ID)).willReturn(List.of(redisOnly));
        given(repository.findDlqFailureLogs(COUPON_ID)).willReturn(List.of());

        // when
        List<AdminCouponDlqFailure> result = service.getDlqFailures(COUPON_ID);

        // then
        assertThat(result).containsExactly(redisOnly);
    }

    @Test
    @DisplayName("존재하지 않는 쿠폰의 DLQ 실패 목록 조회를 거부한다")
    void rejectsDlqFailuresForMissingCoupon() {
        given(repository.existsCoupon(COUPON_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getDlqFailures(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.COUPON_NOT_FOUND));
        verify(dlqFailureRepository, never()).findFailures(COUPON_ID);
    }

    @Test
    @DisplayName("DLQ 실패 항목을 재시도해 저장에 성공하면 failed 스트림에서 제거한다")
    void retriesDlqFailureAndRemovesFromFailedStream() {
        // given
        String recordId = "1735000000000-0";
        MapRecord<String, String, String> record = dlqRecord(recordId, 100L);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(dlqFailureRepository.findOne(COUPON_ID, recordId)).willReturn(Optional.of(record));
        given(issueSyncRepository.saveBatch(eq(COUPON_ID), any()))
                .willAnswer(invocation -> invocation.getArgument(1));

        // when
        AdminCouponDlqRetryResult result = service.retryDlqFailure(COUPON_ID, recordId);

        // then
        assertThat(result).isEqualTo(new AdminCouponDlqRetryResult(COUPON_ID, 100L, true));
        verify(dlqFailureRepository).delete(COUPON_ID, recordId);
    }

    @Test
    @DisplayName("이미 DB에 저장돼 있던 항목을 재시도하면 saved=false로 알리되 failed 스트림에서는 제거한다")
    void retriesAlreadySavedDlqFailureAndStillRemovesIt() {
        // given - saveBatch가 중복 skip으로 빈 목록을 돌려주는 경우
        String recordId = "1735000000000-0";
        MapRecord<String, String, String> record = dlqRecord(recordId, 100L);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(dlqFailureRepository.findOne(COUPON_ID, recordId)).willReturn(Optional.of(record));
        given(issueSyncRepository.saveBatch(eq(COUPON_ID), any())).willReturn(List.of());

        // when
        AdminCouponDlqRetryResult result = service.retryDlqFailure(COUPON_ID, recordId);

        // then
        assertThat(result).isEqualTo(new AdminCouponDlqRetryResult(COUPON_ID, 100L, false));
        verify(dlqFailureRepository).delete(COUPON_ID, recordId);
    }

    @Test
    @DisplayName("존재하지 않는 recordId를 재시도하면 거부하고 saveBatch를 호출하지 않는다")
    void rejectsRetryForMissingRecordId() {
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(dlqFailureRepository.findOne(COUPON_ID, "missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryDlqFailure(COUPON_ID, "missing"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.ISSUE_DLQ_FAILURE_NOT_FOUND));
        verify(issueSyncRepository, never()).saveBatch(eq(COUPON_ID), any());
        verify(dlqFailureRepository, never()).delete(eq(COUPON_ID), any());
    }

    private MapRecord<String, String, String> dlqRecord(String recordId, long memberId) {
        return MapRecord.create(
                        "test-stream",
                        Map.of(
                                "eventId", "event-1",
                                "couponId", Long.toString(COUPON_ID),
                                "memberId", Long.toString(memberId),
                                "issueSequence", "1",
                                "remainingAtIssue", "99",
                                "reservedAtEpochSecond", "1"))
                .withId(RecordId.of(recordId));
    }
}
