package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationResultQueryServiceTest {

    @Mock private VerificationResultQueryRepository repository;
    @InjectMocks private VerificationResultQueryService service;

    @Test
    void returnsVerificationResult() {
        VerificationResultResponse expected =
                new VerificationResultResponse(
                        1L,
                        null,
                        "RUNNING",
                        null,
                        null,
                        LocalDateTime.of(2026, 8, 24, 9, 0),
                        null,
                        0,
                        0,
                        List.of());
        given(repository.findByRunId(1L)).willReturn(Optional.of(expected));

        assertThat(service.getResult(1L)).isEqualTo(expected);
    }

    @Test
    void rejectsMissingVerificationResult() {
        given(repository.findByRunId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.VERIFICATION_RUN_NOT_FOUND));
    }
}
