package com.mocou.issue;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "쿠폰 발급 예약 결과")
public record CouponIssueReservationResult(
        @Schema(
                description = "Redis Stream 예약 이벤트 식별자",
                example = "550e8400-e29b-41d4-a716-446655440000")
        UUID eventId,
        @Schema(description = "쿠폰 회차 ID", example = "301")
        long couponId,
        @Schema(description = "회원 ID", example = "1001")
        long memberId,
        @Schema(description = "Redis 예약 완료 및 DB 비동기 저장 대기 상태", example = "RESERVED")
        CouponIssueReservationStatus status
) {
}
