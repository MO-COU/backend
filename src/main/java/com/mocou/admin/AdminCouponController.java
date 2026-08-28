package com.mocou.admin;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupons")
@Tag(name = "관리자 쿠폰·대시보드 API", description = "관리자 쿠폰 회차와 대시보드 조회 API")
public class AdminCouponController {

    private final AdminCouponService service;

    public AdminCouponController(AdminCouponService service) {
        this.service = service;
    }

    @GetMapping
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

    /** Redis 재고와 DB 적재 현황을 조회한다. */
    @GetMapping("/{couponId}/stock")
    @Operation(
            summary = "쿠폰 재고 조회",
            description = "Redis 재고와 DB 발급 건수, 동기화 차이를 조회합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "쿠폰 재고 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID가 양수가 아님 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰이 존재하지 않음 (COUPON_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503", description = "Redis 조회 불가 (SERVICE_UNAVAILABLE)")
    })
    public ResponseEntity<ApiResponse<AdminCouponStock>> getStock(
            @Parameter(description = "쿠폰 회차 ID", example = "301")
                    @PathVariable
                    long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getStock(couponId)));
    }

    /** DB 발급 이력을 페이지로 조회한다. */
    @GetMapping("/{couponId}/issues")
    @Operation(
            summary = "쿠폰 발급 이력 조회",
            description = "회차별 DB 발급 이력을 선착순 발급 순번으로 조회합니다. 순번이 없는 기존 이력은 마지막에 표시합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "쿠폰 발급 이력 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID 또는 페이지 조건이 올바르지 않음 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰이 존재하지 않음 (COUPON_NOT_FOUND)")
    })
    public ResponseEntity<ApiResponse<AdminCouponIssuePage>> getIssues(
            @Parameter(description = "쿠폰 회차 ID", example = "301")
                    @PathVariable
                    long couponId,
            @Parameter(description = "페이지 번호(0부터)", example = "0")
                    @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20")
                    @RequestParam(defaultValue = "20")
                    int size) {
        return ResponseEntity.ok(ApiResponse.success(service.getIssues(couponId, page, size)));
    }

    @GetMapping("/{couponId}/issue-result-counts")
    @Operation(
            summary = "발급 결과와 DB 적재 진행 조회",
            description = "Redis 발급 결과 누적값과 DB 적재 진행을 함께 조회합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "발급 결과와 DB 적재 진행 조회 성공"),
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

    @GetMapping("/{couponId}/notification-counts")
    @Operation(
            summary = "알림 처리 현황 조회",
            description = "회차별 발급 성공 알림의 전체·완료·대기·실패 건수를 조회합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "알림 처리 현황 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID가 양수가 아님 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰이 존재하지 않음 (COUPON_NOT_FOUND)")
    })
    public ResponseEntity<ApiResponse<AdminCouponNotificationCounts>> getNotificationCounts(
            @Parameter(description = "쿠폰 회차 ID", example = "301")
                    @PathVariable
                    long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getNotificationCounts(couponId)));
    }

    @GetMapping("/{couponId}/issue-dlq/failed")
    @Tag(name = "Issue DLQ", description = "발급 동기화 DLQ 최종 실패 목록 조회 API")
    @Operation(
            summary = "DLQ 최종 실패 목록 조회",
            description =
                    "DLQ 복구 재시도까지 소진해 최종 실패로 확정된 항목을 조회합니다. "
                            + "Redis(issue-dlq-failed Stream)가 기준이며, issue_failure_log 기록이 "
                            + "남아 있으면 실패 사유·시각을 함께 보여줍니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "DLQ 최종 실패 목록 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID가 양수가 아님 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰이 존재하지 않음 (COUPON_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503", description = "Redis 조회 불가 (SERVICE_UNAVAILABLE)")
    })
    public ResponseEntity<ApiResponse<List<AdminCouponDlqFailure>>> getDlqFailures(
            @Parameter(description = "쿠폰 회차 ID", example = "301")
                    @PathVariable
                    long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getDlqFailures(couponId)));
    }

    @PostMapping("/{couponId}/issue-dlq/failed/{recordId}/retry")
    @Tag(name = "Issue DLQ", description = "발급 동기화 DLQ 최종 실패 목록 조회 API")
    @Operation(
            summary = "DLQ 최종 실패 항목 재시도",
            description =
                    "DLQ 실패 목록의 항목 하나를 다시 DB에 저장 시도합니다. 성공하면 failed 스트림에서 "
                            + "제거되고, DB가 아직 살아나지 않았으면 실패 응답과 함께 목록에 그대로 남습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "재시도 처리 성공(저장 완료 또는 이미 처리됨)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID가 양수가 아님 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "쿠폰이 존재하지 않거나(COUPON_NOT_FOUND) DLQ 항목을 찾을 수 없음(ISSUE_DLQ_FAILURE_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500", description = "DB가 아직 복구되지 않아 재시도 저장에 실패함 (SYSTEM_ERROR)")
    })
    public ResponseEntity<ApiResponse<AdminCouponDlqRetryResult>> retryDlqFailure(
            @Parameter(description = "쿠폰 회차 ID", example = "301")
                    @PathVariable
                    long couponId,
            @Parameter(description = "재시도할 DLQ 항목의 Redis Stream record id", example = "1735000000000-0")
                    @PathVariable
                    String recordId) {
        return ResponseEntity.ok(ApiResponse.success(service.retryDlqFailure(couponId, recordId)));
    }
}
