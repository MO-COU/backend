package com.mocou.lifecycle;

import com.mocou.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupon-issues")
public class CouponUseController {

    private final CouponUseService service;

    public CouponUseController(CouponUseService service) {
        this.service = service;
    }

    @PostMapping("/{issueId}/use")
    public ResponseEntity<ApiResponse<CouponUseResult>> use(
            @PathVariable long issueId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.success(service.use(issueId, idempotencyKey)));
    }
}
