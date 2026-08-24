package com.mocou.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mocou.global.exception.ErrorCode;
import com.mocou.global.logging.TraceIdFilter;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.MDC;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.ALWAYS)
/**
 * 모든 API가 공통으로 사용하는 응답 봉투.
 * 컨트롤러는 이 클래스의 정적 팩토리 메서드로만 응답을 만들고, 예외 상황은
 * {@link com.mocou.global.exception.GlobalExceptionHandler}가 대신 error()로 변환해준다.
 */
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorResponse error;
    private final String traceId; // TraceIdFilter가 MDC에 넣어둔 값을 그대로 실어 보냄
    private final OffsetDateTime timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, resolveTraceId(), OffsetDateTime.now());
    }

    /** data가 없는 성공 응답 (예: 상태 변경만 하고 반환값이 없는 API) */
    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(
                false,
                null,
                ErrorResponse.of(errorCode),
                resolveTraceId(),
                OffsetDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(
                false,
                null,
                ErrorResponse.of(errorCode, customMessage),
                resolveTraceId(),
                OffsetDateTime.now());
    }

    // 필터를 안 거치는 경로(배치, 테스트 등)에서 호출되면 MDC에 값이 없을 수 있어 방어적으로 처리
    private static String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId != null && !traceId.isBlank() ? traceId : "unknown";
    }
}
