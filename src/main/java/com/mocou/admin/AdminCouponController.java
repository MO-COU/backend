package com.mocou.admin;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    private final AdminCouponService service;

    public AdminCouponController(AdminCouponService service) {
        this.service = service;
    }

    @GetMapping
    @Tag(name = "Admin Coupon", description = "관리자 쿠폰(회차) 조회 API")
    @Operation(
            summary = "회차 목록 조회",
            description =
                    "만들어진 회차를 최근 순으로 조회합니다. ")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "회차 목록 조회 성공")
    })
    public ResponseEntity<ApiResponse<List<AdminCouponSummary>>> getCoupons() {
        return ResponseEntity.ok(ApiResponse.success(service.getCoupons()));
    }

    @GetMapping("/{couponId}/stock")
    public ResponseEntity<ApiResponse<AdminCouponStock>> getStock(@PathVariable long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getStock(couponId)));
    }

    @GetMapping("/{couponId}/issues")
    public ResponseEntity<ApiResponse<AdminCouponIssuePage>> getIssues(
            @PathVariable long couponId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.getIssues(couponId, page, size)));
    }

    @GetMapping("/{couponId}/issue-result-counts")
    @Tag(name = "Issue Dashboard", description = "Redis 발급 현황 대시보드 API")
    @Operation(
            summary = "Redis 발급 결과 누적 집계 조회",
            description = "Redis가 Lua 스크립트로 집계한 쿠폰별 발급 결과의 현재 누적값을 조회합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "Redis 발급 결과 집계 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID가 양수가 아님 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰이 존재하지 않음 (COUPON_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503", description = "Redis 조회 불가 (SERVICE_UNAVAILABLE)")
    })
    public ResponseEntity<ApiResponse<AdminCouponIssueResultCounts>> getIssueResultCounts(
            @PathVariable long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getIssueResultCounts(couponId)));
    }
}
