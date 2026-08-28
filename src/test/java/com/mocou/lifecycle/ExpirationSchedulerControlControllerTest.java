package com.mocou.lifecycle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExpirationSchedulerControlControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ExpirationSchedulerProperties properties = new ExpirationSchedulerProperties();
        properties.setSchedulerEnabled(false);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new ExpirationSchedulerControlController(
                                        new ExpirationSchedulerState(properties)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("현재 만료 스케줄러 자동 실행 상태를 공통 응답 형식으로 조회한다")
    void getsCurrentSchedulerState() throws Exception {
        mockMvc.perform(get("/api/internal/lifecycle/expiration-scheduler"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("만료 스케줄러 자동 실행 상태를 변경하고 변경된 상태를 반환한다")
    void changesSchedulerState() throws Exception {
        mockMvc.perform(
                        put("/api/internal/lifecycle/expiration-scheduler")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/api/internal/lifecycle/expiration-scheduler"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    @DisplayName("자동 실행 상태가 없으면 공통 입력 오류 응답을 반환한다")
    void rejectsRequestWithoutEnabledState() throws Exception {
        mockMvc.perform(
                        put("/api/internal/lifecycle/expiration-scheduler")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
