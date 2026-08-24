package com.mocou.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 고유 추적 ID를 부여해 MDC에 저장한다.
 * 이후 모든 로그 라인과 ApiResponse에 이 값이 실려서, 부하테스트 중 특정 요청 하나를 로그에서 추적할 수 있다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 다른 필터/컨트롤러가 로그를 남기기 전에 MDC부터 채워둬야 함
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    // 클라이언트가 헤더로 임의의 문자열(로그 인젝션 등)을 보낼 수 있어, 형식이 안 맞으면 신뢰하지 않고 새로 발급한다
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // k6 부하테스트 등에서 X-Trace-Id를 직접 지정해 보내면 그 값을 그대로 쓰고, 없거나 형식이 이상하면 새로 발급
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            // 톰캣 스레드가 재사용되므로, 안 지우면 다음 요청이 이전 요청의 traceId를 이어받는 버그가 생긴다
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
