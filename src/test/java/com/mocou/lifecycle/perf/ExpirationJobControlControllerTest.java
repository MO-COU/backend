package com.mocou.lifecycle.perf;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ExpirationJobControlControllerTest {

    @Mock private ExpirationJobControlService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExpirationJobControlController(service)).build();
    }

    @Test
    @DisplayName("perf capability는 제어 가능 여부와 청크 범위를 반환한다")
    void returnsCapabilities() throws Exception {
        mockMvc.perform(get("/internal/perf/expiration-jobs/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.controlEnabled").value(true))
                .andExpect(jsonPath("$.data.schedulerEnabled").value(false));
    }

    @Test
    @DisplayName("유효한 시작 요청은 Job 완료를 기다리지 않고 202를 반환한다")
    void acceptsJobSubmission() throws Exception {
        given(service.submit("perf-run-1", 2000))
                .willReturn(
                        new ExpirationJobRunSnapshot(
                                "perf-run-1",
                                2000,
                                ExpirationJobRunStatus.SUBMITTED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                java.util.List.of()));

        mockMvc.perform(
                        post("/internal/perf/expiration-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"runKey\":\"perf-run-1\",\"chunkSize\":2000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runKey").value("perf-run-1"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("이미 실행 중인 Job 요청은 409와 원인을 반환한다")
    void returnsConflictWhenJobIsAlreadyRunning() throws Exception {
        // given
        given(service.submit("perf-run-2", 2000))
                .willThrow(new IllegalStateException("JOB_ALREADY_RUNNING"));

        // when, then
        mockMvc.perform(
                        post("/internal/perf/expiration-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"runKey\":\"perf-run-2\",\"chunkSize\":2000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.message").value("JOB_ALREADY_RUNNING"));
    }

    @Test
    @DisplayName("실행기 제출 실패는 503과 원인을 반환한다")
    void returnsServiceUnavailableWhenExecutorRejectsSubmission() throws Exception {
        // given
        given(service.submit("perf-run-3", 2000))
                .willThrow(new IllegalStateException("EXECUTOR_UNAVAILABLE"));

        // when, then
        mockMvc.perform(
                        post("/internal/perf/expiration-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"runKey\":\"perf-run-3\",\"chunkSize\":2000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.message").value("EXECUTOR_UNAVAILABLE"));
    }
}
