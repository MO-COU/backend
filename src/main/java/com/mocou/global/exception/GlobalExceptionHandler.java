package com.mocou.global.exception;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.mocou.global.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessageException(
            HttpMessageNotReadableException exception) {
        log.warn("[UnreadableMessageException] type={}", exception.getClass().getName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("[MethodNotSupportedException] {}", exception.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedisConnectionFailureException(
            RedisConnectionFailureException exception) {
        Throwable cause = exception.getMostSpecificCause();

        log.error(
                "[RedisConnectionFailure] type={}, causeType={}\n{}",
                exception.getClass().getName(),
                cause.getClass().getName(),
                stackFrames(cause));

        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.getStatus())
                .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE));
    }

    // 위 핸들러들에 안 걸리는 모든 예외(NPE, DB 오류 등)에 대한 마지막 로직.
    // 클라이언트에게는 SYSTEM_ERROR라는 안전한 일반 메시지만 내려주고,
    // 실제 원인은 서버 로그에 traceId와 함께 스택 프레임으로 남긴다.
    //
    // exception.getMessage()는 의도적으로 로그에 남기지 않는다 - MySQL UNIQUE 제약 위반 시
    // "Duplicate entry 'user@example.com' for key ..."처럼 예외 메시지에 원본 개인정보가
    // 그대로 담기는 경우가 있어, 이걸 그대로 찍으면 F-COM-001(마스킹)을 로그 레벨에서 위반한다.
    // 원인 추적은 예외 타입 + 발생 위치(스택 프레임) + traceId(MDC, 로그에 자동 포함)만으로 한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("[UnhandledException] type={}\n{}", exception.getClass().getName(), stackFrames(exception));
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.SYSTEM_ERROR));
    }

    private static String stackFrames(Throwable exception) {
        return Arrays.stream(exception.getStackTrace())
                .map(frame -> "\tat " + frame)
                .collect(Collectors.joining("\n"));
    }
}
