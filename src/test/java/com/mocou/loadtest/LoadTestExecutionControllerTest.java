package com.mocou.loadtest;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.exception.GlobalExceptionHandler;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LoadTestExecutionControllerTest {

    @Mock private LoadTestExecutionService service;
    @InjectMocks private LoadTestExecutionController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void startsLoadTest() throws Exception {
        LoadTestRunResponse response = runningResponse();
        given(service.start(new LoadTestStartRequest(301L, LoadTestScenario.V1_RAMP_20000)))
                .willReturn(response);

        mockMvc.perform(
                        post("/api/admin/load-tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "couponId": 301,
                                          "scenario": "V1_RAMP_20000"
                                        }
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runId").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void rejectsMissingScenario() throws Exception {
        mockMvc.perform(
                        post("/api/admin/load-tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "couponId": 301
                                        }
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingCouponId() throws Exception {
        mockMvc.perform(
                        post("/api/admin/load-tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "scenario": "V1_RAMP_20000"
                                        }
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownScenario() throws Exception {
        mockMvc.perform(
                        post("/api/admin/load-tests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "couponId": 301,
                                          "scenario": "SHELL"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void returnsLoadTestResult() throws Exception {
        given(service.getResult(1L)).willReturn(runningResponse());

        mockMvc.perform(get("/api/admin/load-tests/{runId}", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(1))
                .andExpect(jsonPath("$.data.couponId").value(301))
                .andExpect(jsonPath("$.data.users").value(20000));
    }

    private LoadTestRunResponse runningResponse() {
        return new LoadTestRunResponse(
                1L,
                301L,
                LoadTestScenario.V1_RAMP_20000,
                LoadTestRunStatus.RUNNING,
                20000,
                60,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                OffsetDateTime.parse("2026-08-25T10:00:00+09:00"),
                null,
                "부하 테스트를 시작했습니다.");
    }
}
