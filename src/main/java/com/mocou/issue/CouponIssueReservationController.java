package com.mocou.issue;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mocou.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
@Tag(name = "Coupon Issue", description = "회원 쿠폰 발급 예약 API")
public class CouponIssueReservationController {

    private final CouponIssueReservationService service;

    @PostMapping("/{couponId}/issues")
    @Operation(
            summary = "쿠폰 발급 예약",
            description = """
                    Redis Lua에서 발급 시간, 중복 발급, 잔여 재고를 원자적으로 확인합니다.
                    예약 성공 이벤트를 Redis Stream에 이벤트를 저장하고,
                    DB 비동기 저장완료를 기다리지 않고 202 Accepted를 반환합니다.
                    202 응답은 Redis 예약 접수를 의미하며 최종 DB 저장 완료를 의미하지 않습니다.
                    품절·중복·발급 시간 조건 위반 또는 Redis 장애 시에는 원인에 맞는 오류를 반환합니다.
                    """
            )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "Redis 쿠폰 발급 예약 성공 및 DB 비동기 저장 접수(최종 DB 저장 완료 전)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "쿠폰 ID 또는 회원 ID가 올바르지 않음 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "품절, 중복 발급 또는 발급 시작 전 (SOLD_OUT, DUPLICATE, NOT_OPEN_YET)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "410",
                description = "쿠폰 발급 종료 (ISSUE_CLOSED)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description =
                        "Redis 초기화 미완료 또는 Redis 연결 장애 (COUPON_ISSUE_NOT_READY, SERVICE_UNAVAILABLE)")
    })
    public ResponseEntity<ApiResponse<CouponIssueReservationResult>> reserve(
            @Parameter(description = "쿠폰 회차 ID", example = "301") @PathVariable long couponId,
            @Valid @RequestBody CouponIssueReservationRequest request
    ) {
        CouponIssueReservationResult result = service.reserve(couponId, request.memberId());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(result));
    }
}