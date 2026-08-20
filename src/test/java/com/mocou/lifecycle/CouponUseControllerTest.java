package com.mocou.lifecycle;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CouponUseControllerTest {

    private static final long ISSUE_ID = 42L;

    @Mock private CouponUseService service;
    @InjectMocks private CouponUseController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("쿠폰 사용 성공 응답을 반환한다")
    void returnsUsedCoupon() throws Exception {
        // given
        LocalDateTime usedAt = LocalDateTime.of(2026, 8, 18, 15, 30);
        given(service.use(ISSUE_ID, "use-request-1"))
                .willReturn(new CouponUseResult(ISSUE_ID, CouponIssueStatus.USED, usedAt));

        // when, then
        mockMvc.perform(
                        post("/api/coupon-issues/{issueId}/use", ISSUE_ID)
                                .header("Idempotency-Key", "use-request-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.couponIssueId").value(ISSUE_ID))
                .andExpect(jsonPath("$.data.status").value("USED"))
                .andExpect(jsonPath("$.data.usedAt").value("2026-08-18T15:30:00"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("멱등성 키가 없으면 입력 오류 응답을 반환한다")
    void mapsMissingIdempotencyKeyToInvalidInput() throws Exception {
        // given
        given(service.use(ISSUE_ID, null))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when, then
        mockMvc.perform(post("/api/coupon-issues/{issueId}/use", ISSUE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    @DisplayName("쿠폰 사용 도메인 오류를 HTTP 상태로 변환한다")
    void mapsCouponUseErrors(ErrorCode errorCode, int expectedStatus) throws Exception {
        // given
        given(service.use(ISSUE_ID, "error-request"))
                .willThrow(new BusinessException(errorCode));

        // when, then
        mockMvc.perform(
                        post("/api/coupon-issues/{issueId}/use", ISSUE_ID)
                                .header("Idempotency-Key", "error-request"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(errorCode.name()))
                .andExpect(jsonPath("$.error.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private static Stream<Arguments> errorCases() {
        return Stream.of(
                Arguments.of(ErrorCode.INVALID_INPUT, 400),
                Arguments.of(ErrorCode.ISSUE_NOT_FOUND, 404),
                Arguments.of(ErrorCode.IDEMPOTENCY_CONFLICT, 409),
                Arguments.of(ErrorCode.INVALID_STATE_TRANSITION, 409),
                Arguments.of(ErrorCode.COUPON_EXPIRED, 410));
    }
}
