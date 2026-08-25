package com.mocou.admin;

import com.mocou.global.response.ApiResponse;
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
    public ResponseEntity<ApiResponse<AdminCouponIssueResultCounts>> getIssueResultCounts(
            @PathVariable long couponId) {
        return ResponseEntity.ok(ApiResponse.success(service.getIssueResultCounts(couponId)));
    }
}
