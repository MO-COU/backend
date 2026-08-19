package com.mocou.global.exception;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.logging.TraceIdFilter;
import com.mocou.lifecycle.CouponUseController;
import com.mocou.lifecycle.CouponUseErrorCode;
import com.mocou.lifecycle.CouponUseException;
import com.mocou.lifecycle.CouponUseExceptionHandler;
import com.mocou.lifecycle.CouponUseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private CouponUseService couponUseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController(), new CouponUseController(couponUseService))
                .setControllerAdvice(
                        new GlobalExceptionHandler(), new CouponUseExceptionHandler())
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
    @DisplayName("기존 CouponUseException은 B팀 전용 Handler가 계속 처리한다")
    void keepsExistingCouponUseExceptionHandler() throws Exception {
        // given
        given(couponUseService.use(42L, null))
                .willThrow(new CouponUseException(CouponUseErrorCode.INVALID_INPUT));

        // when & then
        mockMvc.perform(post("/api/v1/coupon-issues/{issueId}/use", 42L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
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
    }

    record TestRequest(@NotBlank(message = "이름은 필수입니다") String name) {}
}
