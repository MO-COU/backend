package com.mocou.lifecycle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CouponUseController.class)
public class CouponUseExceptionHandler {

    @ExceptionHandler(CouponUseException.class)
    public ResponseEntity<CouponUseErrorResponse> handle(CouponUseException exception) {
        return ResponseEntity.status(statusOf(exception.errorCode()))
                .body(CouponUseErrorResponse.from(exception));
    }

    private HttpStatus statusOf(CouponUseErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case ISSUE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, INVALID_STATE_TRANSITION -> HttpStatus.CONFLICT;
            case COUPON_EXPIRED -> HttpStatus.GONE;
        };
    }
}
