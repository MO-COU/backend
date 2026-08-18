package com.mocou.lifecycle;

public enum CouponUseErrorCode {
    INVALID_INPUT("요청 값이 올바르지 않습니다."),
    ISSUE_NOT_FOUND("발급된 쿠폰을 찾을 수 없습니다."),
    IDEMPOTENCY_CONFLICT("멱등성 키가 다른 상태 전이에 사용되었습니다."),
    INVALID_STATE_TRANSITION("현재 상태에서는 쿠폰을 사용할 수 없습니다."),
    COUPON_EXPIRED("만료된 쿠폰은 사용할 수 없습니다.");

    private final String message;

    CouponUseErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
