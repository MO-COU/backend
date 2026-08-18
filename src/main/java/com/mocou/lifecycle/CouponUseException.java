package com.mocou.lifecycle;

public final class CouponUseException extends RuntimeException {

    private final CouponUseErrorCode errorCode;

    public CouponUseException(CouponUseErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public CouponUseErrorCode errorCode() {
        return errorCode;
    }
}
