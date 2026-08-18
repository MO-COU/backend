package com.mocou.lifecycle;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CouponUseControllerTest {

    private static final long ISSUE_ID = 42L;

    private CouponUseService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(CouponUseService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new CouponUseController(service))
                        .setControllerAdvice(new CouponUseExceptionHandler())
                        .build();
    }

    @Test
    void returnsUsedCoupon() throws Exception {
        LocalDateTime usedAt = LocalDateTime.of(2026, 8, 18, 15, 30);
        when(service.use(ISSUE_ID, "use-request-1"))
                .thenReturn(new CouponUseResult(ISSUE_ID, CouponIssueStatus.USED, usedAt));

        mockMvc.perform(
                        post("/api/v1/coupon-issues/{issueId}/use", ISSUE_ID)
                                .header("Idempotency-Key", "use-request-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.couponIssueId").value(ISSUE_ID))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.usedAt").value("2026-08-18T15:30:00"));
    }

    @Test
    void mapsMissingIdempotencyKeyToInvalidInput() throws Exception {
        when(service.use(ISSUE_ID, null))
                .thenThrow(new CouponUseException(CouponUseErrorCode.INVALID_INPUT));

        mockMvc.perform(post("/api/v1/coupon-issues/{issueId}/use", ISSUE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    void mapsCouponUseErrors(CouponUseErrorCode errorCode, int expectedStatus) throws Exception {
        when(service.use(ISSUE_ID, "error-request"))
                .thenThrow(new CouponUseException(errorCode));

        mockMvc.perform(
                        post("/api/v1/coupon-issues/{issueId}/use", ISSUE_ID)
                                .header("Idempotency-Key", "error-request"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(errorCode.name()))
                .andExpect(jsonPath("$.message").value(errorCode.message()));
    }

    private static Stream<Arguments> errorCases() {
        return Stream.of(
                Arguments.of(CouponUseErrorCode.INVALID_INPUT, 400),
                Arguments.of(CouponUseErrorCode.ISSUE_NOT_FOUND, 404),
                Arguments.of(CouponUseErrorCode.IDEMPOTENCY_CONFLICT, 409),
                Arguments.of(CouponUseErrorCode.INVALID_STATE_TRANSITION, 409),
                Arguments.of(CouponUseErrorCode.COUPON_EXPIRED, 410));
    }
}
