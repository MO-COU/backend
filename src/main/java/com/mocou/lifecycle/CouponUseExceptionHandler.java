package com.mocou.lifecycle;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// CouponUseController 전용으로 좁게 스코프된 핸들러라, global.GlobalExceptionHandler의
// catch-all(Exception.class)보다 항상 먼저 시도되도록 우선순위를 명시적으로 높여둔다.
// (둘 다 순서 미지정이면 동점 처리 순서가 보장되지 않아 catch-all이 먼저 걸릴 수 있음)
@Order(Ordered.HIGHEST_PRECEDENCE)
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
