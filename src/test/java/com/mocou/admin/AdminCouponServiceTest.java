package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCouponServiceTest {

    private static final long COUPON_ID = 10L;

    @Mock private AdminCouponRepository repository;
    @Mock private AdminCouponRealtimeStockRepository realtimeStockRepository;
    @Mock private RedisAdminCouponIssueResultRepository issueResultRepository;
    @InjectMocks private AdminCouponService service;

    @Test
    @DisplayName("존재하는 쿠폰의 실시간 발급 결과를 조회한다")
    void returnsRealtimeIssueResultCounts() {
        // given
        AdminCouponIssueResultCounts counts =
                AdminCouponIssueResultCounts.of(COUPON_ID, 8_320, 1_200, 420, 30, 30, 0, 0, 20);
        given(repository.existsCoupon(COUPON_ID)).willReturn(true);
        given(issueResultRepository.findCounts(COUPON_ID)).willReturn(counts);

        // when
        AdminCouponIssueResultCounts result = service.getIssueResultCounts(COUPON_ID);

        // then
        assertThat(result).isEqualTo(counts);
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
                        LocalDateTime.of(2026, 8, 26, 10, 0));
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

}
