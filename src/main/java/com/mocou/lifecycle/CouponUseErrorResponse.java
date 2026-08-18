package com.mocou.lifecycle;

public record CouponUseErrorResponse(String code, String message) {

    static CouponUseErrorResponse from(CouponUseException exception) {
        return new CouponUseErrorResponse(
                exception.errorCode().name(), exception.errorCode().message());
    }
}
