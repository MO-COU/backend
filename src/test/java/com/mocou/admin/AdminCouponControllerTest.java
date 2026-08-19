package com.mocou.admin;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminCouponControllerTest {

    private static final long COUPON_ID = 10L;

    @Mock private AdminCouponService service;
    @InjectMocks private AdminCouponController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("쿠폰 발급 이력을 공통 응답 형식으로 반환한다")
    void returnsCouponIssues() throws Exception {
        // given
        AdminCouponIssue issue =
                new AdminCouponIssue(
                        30L,
                        COUPON_ID,
                        100L,
                        "ISSUED",
                        LocalDateTime.of(2026, 8, 19, 10, 0),
                        null,
                        LocalDateTime.of(2026, 8, 26, 10, 0));
        given(service.getIssues(COUPON_ID, 0, 20))
                .willReturn(new AdminCouponIssuePage(List.of(issue), 0, 20, 1, 1, false));

        // when, then
        mockMvc.perform(get("/api/admin/coupons/{couponId}/issues", COUPON_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].issueId").value(30L))
                .andExpect(jsonPath("$.data.content[0].memberId").value(100L))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("DB 기준 쿠폰 재고를 공통 응답 형식으로 반환한다")
    void returnsCouponStock() throws Exception {
        // given
        AdminCouponStock stock =
                new AdminCouponStock(
                        COUPON_ID,
                        10_000,
                        8_000,
                        2_000,
                        "OPEN",
                        LocalDateTime.of(2026, 8, 19, 15, 0));
        given(service.getStock(COUPON_ID)).willReturn(stock);

        // when, then
        mockMvc.perform(get("/api/admin/coupons/{couponId}/stock", COUPON_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalQuantity").value(10_000))
                .andExpect(jsonPath("$.data.issuedQuantity").value(8_000))
                .andExpect(jsonPath("$.data.remainingQuantity").value(2_000))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-08-19T15:00:00"));
    }

}
