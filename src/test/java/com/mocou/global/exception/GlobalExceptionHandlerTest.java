package com.mocou.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mocou.global.logging.TraceIdFilter;
import com.mocou.lifecycle.CouponUseController;
import com.mocou.lifecycle.CouponUseService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private CouponUseService couponUseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController(), new CouponUseController(couponUseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("BusinessException을 ErrorCode 상태와 공통 응답으로 변환한다")
    void handlesBusinessException() throws Exception {
        // when & then
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("요청 DTO 검증 실패를 INVALID_INPUT 공통 응답으로 변환한다")
    void handlesInvalidRequestBody() throws Exception {
        // when & then
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("이름은 필수입니다"));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드를 METHOD_NOT_ALLOWED로 변환한다")
    void handlesUnsupportedMethod() throws Exception {
        // when & then
        mockMvc.perform(post("/test/method"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("쿠폰 사용 BusinessException을 공통 응답으로 변환한다")
    void handlesCouponUseBusinessException() throws Exception {
        // given
        given(couponUseService.use(42L, null))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when & then
        mockMvc.perform(post("/api/coupon-issues/{issueId}/use", 42L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("예상치 못한 예외는 SYSTEM_ERROR로 응답하고, 원본 메시지는 로그에 남기지 않는다")
    void handlesUnexpectedExceptionWithoutLeakingMessage() throws Exception {
        // given
        String piiMessage = "Duplicate entry 'user@example.com' for key 'uk_member_email'";
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            mockMvc.perform(get("/test/unexpected"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SYSTEM_ERROR"))
                    .andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했습니다"));

            // then
            String logged =
                    appender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(Collectors.joining("\n"));
            assertThat(logged).doesNotContain("user@example.com");
            assertThat(logged).contains("IllegalStateException");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Redis 연결 실패를 SERVICE_UNAVAILABLE 공통 응답으로 변환하고 ERROR 로그를 남긴다")
    void handlesRedisConnectionFailure() throws Exception {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when, then
            mockMvc.perform(get("/test/redis-unavailable"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.error.code")
                            .value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message")
                            .value("서비스를 일시적으로 사용할 수 없습니다"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());

            assertThat(appender.list)
                    .anySatisfy(
                            event -> {
                                assertThat(event.getLevel())
                                        .isEqualTo(Level.ERROR);
                                assertThat(event.getFormattedMessage())
                                        .contains("RedisConnectionFailure")
                                        .contains("IllegalStateException");
                            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody TestRequest request) {}

        @GetMapping("/method")
        void method() {}

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("Duplicate entry 'user@example.com' for key 'uk_member_email'");
        }

        @GetMapping("/redis-unavailable")
        void redisUnavailable() {
            throw new RedisConnectionFailureException("Redis 연결 실패", new IllegalStateException("Connection refused"));
        }

    }

    record TestRequest(@NotBlank(message = "이름은 필수입니다") String name) {}
}
