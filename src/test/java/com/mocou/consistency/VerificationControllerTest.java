package com.mocou.consistency;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mocou.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class VerificationControllerTest {

    @Mock private VerificationLauncher launcher;
    @Mock private VerificationResultQueryService queryService;
    @InjectMocks private VerificationController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void returnsVerificationResult() throws Exception {
        VerificationResultResponse response =
                new VerificationResultResponse(
                        11L,
                        null,
                        "COMPLETED",
                        "PASS",
                        LocalDateTime.of(2026, 8, 24, 9, 1),
                        LocalDateTime.of(2026, 8, 24, 9, 0),
                        LocalDateTime.of(2026, 8, 24, 9, 2),
                        100,
                        0,
                        List.of());
        given(queryService.getResult(11L)).willReturn(response);

        mockMvc.perform(get("/api/admin/verifications/{runId}", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runId").value(11L))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.verdict").value("PASS"))
                .andExpect(jsonPath("$.data.checkedCount").value(100));
    }
}
