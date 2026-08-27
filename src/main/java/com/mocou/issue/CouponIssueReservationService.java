package com.mocou.issue;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueReservationService {

    private final RedisCouponIssueGateway redisCouponIssueGateway;

    /**
     * reserve(couponId, memberId)
     * -> iD 검증
     * -> UUID eventId 생성
     * -> Redis Lua + Stream 호출
     * -> Redis 결과 확인
     *      -> RESERVED: 예약 정보 반환
     *      -> 나머지 : BusinessException 발생
     */
    public CouponIssueReservationResult reserve(long couponId, long memberId) {
        validateIds(couponId, memberId);

        UUID eventId = UUID.randomUUID();
        CouponReservationResult reservationResult = redisCouponIssueGateway.reserveAndAppendEvent(couponId, memberId, eventId);

        return switch (reservationResult) {
            case RESERVED ->
                    new CouponIssueReservationResult(
                            eventId,
                            couponId,
                            memberId,
                            CouponIssueReservationStatus.RESERVED);

            case SOLD_OUT ->
                    throw new BusinessException(
                            ErrorCode.SOLD_OUT);

            case DUPLICATE_ISSUE ->
                    throw new BusinessException(
                            ErrorCode.DUPLICATE);

            case NOT_OPEN_YET ->
                    throw new BusinessException(
                            ErrorCode.NOT_OPEN_YET);

            case ISSUE_CLOSED ->
                    throw new BusinessException(
                            ErrorCode.ISSUE_CLOSED);

            case STOCK_NOT_INITIALIZED,
                    METADATA_NOT_INITIALIZED ->
                    throw new BusinessException(
                            ErrorCode.COUPON_ISSUE_NOT_READY);
        };
    }

    private void validateIds(
            long couponId,
            long memberId
    ) {
        if (couponId <= 0 || memberId <= 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT);
        }
    }
}
