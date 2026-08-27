package com.mocou.issue;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "쿠폰 발급 예약 상태")
public enum CouponIssueReservationStatus {
    @Schema(description = "Redis 예약 성공 및 DB 비동기 저장 대기 상태")
    RESERVED
}
