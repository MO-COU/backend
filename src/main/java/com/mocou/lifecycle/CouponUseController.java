package com.mocou.lifecycle;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupon-issues")
@Tag(name = "사용자·쿠폰 API", description = "사용자 쿠폰 발급과 사용 API")
public class CouponUseController {

    private final CouponUseService service;

    public CouponUseController(CouponUseService service) {
        this.service = service;
    }

    @PostMapping("/{issueId}/use")
    @Operation(
            summary = "쿠폰 사용 처리",
            description =
                    "발급된 쿠폰을 사용 완료 상태로 변경합니다. Idempotency-Key를 함께 보내면 동일 요청의 재시도는 "
                            + "같은 결과로 처리됩니다. 서로 다른 상태 전이에 같은 키를 사용하면 충돌로 거부됩니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "쿠폰 사용 처리 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "멱등성 키가 없거나 입력값이 올바르지 않음 (INVALID_INPUT)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "발급된 쿠폰을 찾을 수 없음 (ISSUE_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "멱등성 키 충돌 또는 현재 상태에서 사용 불가 (IDEMPOTENCY_CONFLICT, INVALID_STATE_TRANSITION)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "410", description = "만료된 쿠폰은 사용할 수 없음 (COUPON_EXPIRED)")
    })
    public ResponseEntity<ApiResponse<CouponUseResult>> use(
            @Parameter(description = "발급된 쿠폰 ID", example = "42") @PathVariable long issueId,
            @Parameter(
                            description = "재시도 요청을 식별하는 멱등성 키. 사용 처리 시 필수입니다.",
                            example = "use-request-1",
                            required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.success(service.use(issueId, idempotencyKey)));
    }
}
