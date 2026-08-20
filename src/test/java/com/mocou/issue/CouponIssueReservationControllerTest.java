package com.mocou.issue;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.global.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
public class CouponIssueReservationControllerTest {

    private static final long COUPON_ID = 1L;
    private static final long MEMBER_ID = 100L;
    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private CouponIssueReservationService service;

    @InjectMocks
    private CouponIssueReservationController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("쿠폰 예약 성공 시 202 Accepted를 반환한다")
    void returnsAcceptedReservation() throws Exception {
        // given
        given(service.reserve(COUPON_ID, MEMBER_ID))
                .willReturn(new CouponIssueReservationResult(
                        EVENT_ID,
                        COUPON_ID,
                        MEMBER_ID,
                        CouponIssueReservationStatus.RESERVED));

        // when, then
        mockMvc.perform(
                        post("/api/coupons/{couponId}/issues", COUPON_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "memberId": 100
                                        }
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId")
                        .value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.couponId")
                        .value(COUPON_ID))
                .andExpect(jsonPath("$.data.memberId")
                        .value(MEMBER_ID))
                .andExpect(jsonPath("$.data.status")
                        .value("RESERVED"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("유효하지 않은 회원 ID는 400 응답을 반환한다")
    void rejectsInvalidMemberId(String requestBody) throws Exception {
        // when, then
        mockMvc.perform(
                        post("/api/coupons/{couponId}/issues", COUPON_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_INPUT"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    @DisplayName("발급 예약 오류를 공통 HTTP 오류 응답으로 변환한다")
    void mapsReservationErrors(
            ErrorCode errorCode,
            int expectedStatus
    ) throws Exception {
        // given
        given(service.reserve(COUPON_ID, MEMBER_ID))
                .willThrow(new BusinessException(errorCode));

        // when, then
        mockMvc.perform(
                        post("/api/coupons/{couponId}/issues", COUPON_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "memberId": 100
                                        }
                                        """))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value(errorCode.name()))
                .andExpect(jsonPath("$.error.message")
                        .value(errorCode.getMessage()));
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{}"),
                Arguments.of("""
                        {
                          "memberId": 0
                        }
                        """),
                Arguments.of("""
                        {
                          "memberId": -1
                        }
                        """)
        );
    }

    private static Stream<Arguments> errorCases() {
        return Stream.of(
                Arguments.of(ErrorCode.SOLD_OUT, 409),
                Arguments.of(ErrorCode.DUPLICATE, 409),
                Arguments.of(ErrorCode.NOT_OPEN_YET, 409),
                Arguments.of(ErrorCode.ISSUE_CLOSED, 410),
                Arguments.of(ErrorCode.COUPON_ISSUE_NOT_READY, 503)
        );
    }
}