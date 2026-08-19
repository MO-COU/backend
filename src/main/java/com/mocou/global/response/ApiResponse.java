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
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorResponse error;
    private final String traceId;
    private final OffsetDateTime timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, resolveTraceId(), OffsetDateTime.now());
    }

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

    private static String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId != null && !traceId.isBlank() ? traceId : "unknown";
    }
}
