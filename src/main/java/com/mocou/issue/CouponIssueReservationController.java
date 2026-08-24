package com.mocou.issue;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mocou.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponIssueReservationController {

    private final CouponIssueReservationService service;

    @PostMapping("/{couponId}/issues")
    public ResponseEntity<ApiResponse<CouponIssueReservationResult>> reserve(
            @PathVariable long couponId,
            @Valid @RequestBody CouponIssueReservationRequest request
    ) {
        CouponIssueReservationResult result = service.reserve(couponId, request.memberId());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(result));
    }
}