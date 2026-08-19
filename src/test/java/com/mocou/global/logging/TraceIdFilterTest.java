package com.mocou.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("traceId가 없으면 생성하여 응답 헤더와 MDC에 전달한다")
    void createsTraceIdWhenHeaderIsMissing() throws ServletException, IOException {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        // when
        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        traceIdInChain.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)));

        // then
        assertThat(traceIdInChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(traceIdInChain.get());
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("요청 헤더의 traceId가 있으면 같은 값을 사용한다")
    void reusesTraceIdFromRequestHeader() throws ServletException, IOException {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "client-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        // when
        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        traceIdInChain.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)));

        // then
        assertThat(traceIdInChain.get()).isEqualTo("client-trace-id");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo("client-trace-id");
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("허용되지 않는 요청 traceId 대신 새로운 값을 생성한다")
    void replacesInvalidTraceId() throws ServletException, IOException {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "invalid trace id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        // when
        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        traceIdInChain.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)));

        // then
        assertThat(traceIdInChain.get())
                .isNotBlank()
                .isNotEqualTo("invalid trace id");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(traceIdInChain.get());
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }
}
