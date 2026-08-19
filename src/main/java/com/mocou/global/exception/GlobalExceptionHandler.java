package com.mocou.global.exception;

import com.mocou.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러에서 개별적으로 try-catch 하지 않아도, 여기서 예외를 잡아 ApiResponse 형식으로 통일해서 내려준다.
 * 마지막 handleException()이 예상치 못한 예외까지 전부 잡아서, 어떤 경우에도 응답 형식이 깨지지 않게 한다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE) // 다른 @ControllerAdvice가 있다면 그쪽이 먼저 처리하게 우선순위를 가장 낮게 둠
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn("[BusinessException] Code: {}, Message: {}", errorCode.name(), exception.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception) {
        String errorMessage = exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        log.warn("[ValidationException] Message: {}", errorMessage);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, errorMessage));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("[MethodNotSupportedException] {}", exception.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    // 위 핸들러들에 안 걸리는 모든 예외(NPE, DB 오류 등)에 대한 마지막 로직.
    // 클라이언트에게는 SYSTEM_ERROR라는 안전한 일반 메시지만 내려주고,
    // 실제 원인은 서버 로그에 traceId와 함께 전체 스택트레이스로 남긴다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("[UnhandledException]", exception);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.SYSTEM_ERROR));
    }
}
