package com.mocou.coupon;

import com.mocou.global.response.ApiResponse;
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
public class CouponRoundController {

    private final CouponRoundService couponRoundService;

    /**
     * 회차를 만들고 발급 가능한 상태로 둔다.
     *
     * <p>응답의 {@code couponId}를 부하 테스트에 넘긴다. 만들어지자마자 발급을 받을 수 있으므로 {@code 201}로 답한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CouponRoundResponse>> create(
            @Valid @RequestBody CouponRoundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(couponRoundService.create(request)));
    }
}
