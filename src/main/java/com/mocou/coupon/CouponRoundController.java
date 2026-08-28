package com.mocou.coupon;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회차를 추가한다.
 *
 * <p>부하 테스트를 조건을 바꿔가며 여러 번 돌리려면 회차를 직접 만들 수 있어야 한다. {@code datagen}을 다시 돌리면 회원
 * 100만과 발급 300만까지 통째로 다시 만들게 된다.
 */
@RestController
@RequestMapping("/api/admin/coupons")
@RequiredArgsConstructor
@Tag(name = "관리자 쿠폰·대시보드 API", description = "관리자 쿠폰 회차와 대시보드 조회 API")
public class CouponRoundController {

    private final CouponRoundService couponRoundService;

    /**
     * 회차를 만들고 발급 가능한 상태로 둔다.
     *
     * <p>응답의 {@code couponId}를 부하 테스트에 넘긴다. 만들어지자마자 발급을 받을 수 있으므로 {@code 201}로 답한다.
     */
    @PostMapping
    @Operation(
            summary = "회차 추가",
            description =
                    "재고와 오픈 시각을 받아 회차를 만들고 Redis 재고·발급 시각까지 세운다. "
                            + "응답의 couponId를 부하 테스트에 넘긴다(k6의 COUPON_ID). "
                            + "오픈 시각을 현재로 주면 즉시 발급받을 수 있는 회차가 된다. "
                            + "closeAt을 비우면 오픈 당일 23:59:59, name을 비우면 회차 번호로 채운다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "회차 생성 완료. Redis까지 세워져 바로 발급받을 수 있다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "재고 또는 오픈 시각 누락, 재고가 1 미만, 종료 시각이 시작보다 앞섬")
    })
    public ResponseEntity<ApiResponse<CouponRoundResponse>> create(
            @Valid @RequestBody CouponRoundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(couponRoundService.create(request)));
    }
}
