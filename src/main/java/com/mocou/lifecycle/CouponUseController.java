package com.mocou.lifecycle;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupon-issues")
public class CouponUseController {

    private final CouponUseService service;

    public CouponUseController(CouponUseService service) {
        this.service = service;
    }

    @PostMapping("/{issueId}/use")
    public ResponseEntity<CouponUseResult> use(
            @PathVariable long issueId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(service.use(issueId, idempotencyKey));
    }
}
