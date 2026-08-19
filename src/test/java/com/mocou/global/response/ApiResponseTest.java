package com.mocou.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.global.exception.ErrorCode;
import com.mocou.global.logging.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ApiResponseTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("성공 응답은 데이터와 traceId를 포함한다")
    void successContainsDataAndTraceId() {
        // given
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-123");

        // when
        ApiResponse<String> response = ApiResponse.success("result");

        // then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("result");
        assertThat(response.getError()).isNull();
        assertThat(response.getTraceId()).isEqualTo("trace-123");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("실패 응답은 에러 정보와 traceId를 포함한다")
    void errorContainsErrorAndTraceId() {
        // given
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-456");

        // when
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.SOLD_OUT);

        // then
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getError().code()).isEqualTo("SOLD_OUT");
        assertThat(response.getError().message()).isEqualTo("재고가 소진되었습니다");
        assertThat(response.getTraceId()).isEqualTo("trace-456");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("데이터 없는 성공 응답을 생성할 수 있다")
    void successWithoutData() {
        // when
        ApiResponse<Void> response = ApiResponse.success();

        // then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
        assertThat(response.getError()).isNull();
        assertThat(response.getTraceId()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("실패 응답에 사용자 지정 메시지를 넣을 수 있다")
    void errorWithCustomMessage() {
        // when
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_INPUT, "쿠폰 ID는 필수입니다");

        // then
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError().code()).isEqualTo("INVALID_INPUT");
        assertThat(response.getError().message()).isEqualTo("쿠폰 ID는 필수입니다");
    }

    @Test
    @DisplayName("MDC의 traceId가 비어 있으면 unknown을 사용한다")
    void usesUnknownWhenTraceIdIsBlank() {
        // given
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, " ");

        // when
        ApiResponse<Void> response = ApiResponse.success();

        // then
        assertThat(response.getTraceId()).isEqualTo("unknown");
    }
}
