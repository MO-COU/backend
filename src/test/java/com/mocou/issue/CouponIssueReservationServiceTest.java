package com.mocou.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
public class CouponIssueReservationServiceTest {

    private static final long COUPON_ID = 1L;
    private static final long MEMBER_ID = 100L;

    @Mock
    private RedisCouponIssueGateway redisCouponIssueGateway;

    @InjectMocks
    private CouponIssueReservationService service;

    @Test
    @DisplayName("Redis 예약에 성공하면 RESERVED 결과를 반환한다")
    void reservesCoupon() {
        // given
        given(redisCouponIssueGateway.reserveAndAppendEvent(
                org.mockito.ArgumentMatchers.eq(COUPON_ID),
                org.mockito.ArgumentMatchers.eq(MEMBER_ID),
                org.mockito.ArgumentMatchers.any(UUID.class)))
                .willReturn(CouponReservationResult.RESERVED);

        // when
        CouponIssueReservationResult result =
                service.reserve(COUPON_ID, MEMBER_ID);

        // then
        assertThat(result.eventId()).isNotNull();
        assertThat(result.couponId()).isEqualTo(COUPON_ID);
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.status())
                .isEqualTo(CouponIssueReservationStatus.RESERVED);

        verify(redisCouponIssueGateway)
                .reserveAndAppendEvent(
                        COUPON_ID,
                        MEMBER_ID,
                        result.eventId());
    }

    @ParameterizedTest
    @MethodSource("reservationFailures")
    @DisplayName("Redis 예약 실패 결과를 공통 BusinessException으로 변환한다")
    void mapsReservationFailure(
            CouponReservationResult reservationResult,
            ErrorCode expectedErrorCode
    ) {
        // given
        given(redisCouponIssueGateway.reserveAndAppendEvent(
                org.mockito.ArgumentMatchers.eq(COUPON_ID),
                org.mockito.ArgumentMatchers.eq(MEMBER_ID),
                org.mockito.ArgumentMatchers.any(UUID.class)))
                .willReturn(reservationResult);

        // when, then
        assertThatThrownBy(() ->
                service.reserve(COUPON_ID, MEMBER_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expectedErrorCode));
    }

    @ParameterizedTest
    @CsvSource({
        "0, 100",
        "-1, 100",
        "1, 0",
        "1, -1"
    })
    @DisplayName("쿠폰 ID나 회원 ID가 양수가 아니면 Redis를 호출하지 않는다")
    void rejectsInvalidIds(long couponId, long memberId) {
        // when, then
        assertThatThrownBy(() ->
                service.reserve(couponId, memberId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT));

        verifyNoInteractions(redisCouponIssueGateway);
    }

    private static Stream<Arguments> reservationFailures() {
        return Stream.of(
                Arguments.of(
                        CouponReservationResult.SOLD_OUT,
                        ErrorCode.SOLD_OUT),
                Arguments.of(
                        CouponReservationResult.DUPLICATE_ISSUE,
                        ErrorCode.DUPLICATE),
                Arguments.of(
                        CouponReservationResult.NOT_OPEN_YET,
                        ErrorCode.NOT_OPEN_YET),
                Arguments.of(
                        CouponReservationResult.ISSUE_CLOSED,
                        ErrorCode.ISSUE_CLOSED),
                Arguments.of(
                        CouponReservationResult.STOCK_NOT_INITIALIZED,
                        ErrorCode.COUPON_ISSUE_NOT_READY),
                Arguments.of(
                        CouponReservationResult.METADATA_NOT_INITIALIZED,
                        ErrorCode.COUPON_ISSUE_NOT_READY)
        );
    }
}