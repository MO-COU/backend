package com.mocou.lifecycle.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.mocou.lifecycle.ExpirationClock;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

@ExtendWith(MockitoExtension.class)
class ExpirationJobControlServiceTest {

    private static final LocalDateTime DATABASE_TIME = LocalDateTime.of(2026, 8, 21, 10, 0);

    @Mock private JobOperator jobOperator;
    @Mock private JobRepository jobRepository;
    @Mock private Job couponExpirationJob;
    @Mock private ExpirationClock expirationClock;
    @Mock private JobExecution jobExecution;

    @Test
    @DisplayName("새 runKey는 DB 시각과 요청 청크 크기로 새 Batch를 실행한다")
    void startsNewJobWithDatabaseTimeAndRequestedChunkSize() throws Exception {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob")).willReturn(Set.of());
        given(expirationClock.now()).willReturn(DATABASE_TIME);
        given(jobOperator.start(eq(couponExpirationJob), org.mockito.ArgumentMatchers.any(JobParameters.class)))
                .willReturn(jobExecution);
        given(jobExecution.getId()).willReturn(42L);
        given(jobExecution.getStatus()).willReturn(BatchStatus.COMPLETED);
        ExpirationJobControlService service =
                new ExpirationJobControlService(
                        jobOperator,
                        jobRepository,
                        couponExpirationJob,
                        expirationClock,
                        new ExpirationJobRunRegistry(),
                        Runnable::run);
        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);

        // when
        service.submit("perf-run-1", 2000);

        // then
        verify(jobOperator).start(eq(couponExpirationJob), parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getString("runKey")).isEqualTo("perf-run-1");
        assertThat(parametersCaptor.getValue().getLong("chunkSize")).isEqualTo(2000L);
        assertThat(parametersCaptor.getValue().getLocalDateTime("cutoffAt")).isEqualTo(DATABASE_TIME);
        assertThat(service.find("perf-run-1").status()).isEqualTo(ExpirationJobRunStatus.COMPLETED);
        assertThat(service.find("perf-run-1").jobExecutionId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("DB 시각 조회가 실패하면 실행 상태를 FAILED로 남기고 다음 실행을 막지 않는다")
    void recordsFailedStatusWhenDatabaseClockFails() {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob")).willReturn(Set.of());
        given(expirationClock.now()).willThrow(new IllegalStateException("database unavailable"));
        ExpirationJobRunRegistry registry = new ExpirationJobRunRegistry();
        ExpirationJobControlService service =
                new ExpirationJobControlService(
                        jobOperator,
                        jobRepository,
                        couponExpirationJob,
                        expirationClock,
                        registry,
                        Runnable::run);

        // when
        service.submit("failed-run", 2000);
        ExpirationJobReservation next = registry.reserve("next-run", 2000);

        // then
        assertThat(service.find("failed-run").status()).isEqualTo(ExpirationJobRunStatus.FAILED);
        assertThat(service.find("failed-run").failureReason()).isEqualTo("JOB_EXECUTION_FAILED");
        assertThat(next.status()).isEqualTo(ExpirationJobReservationStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Batch 실행이 실패하면 FAILED 상태와 원인을 기록한다")
    void recordsFailedStatusWhenBatchExecutionFails() throws Exception {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob")).willReturn(Set.of());
        given(expirationClock.now()).willReturn(DATABASE_TIME);
        given(jobOperator.start(eq(couponExpirationJob), org.mockito.ArgumentMatchers.any(JobParameters.class)))
                .willReturn(jobExecution);
        given(jobExecution.getStatus()).willReturn(BatchStatus.FAILED);
        ExpirationJobControlService service =
                new ExpirationJobControlService(
                        jobOperator,
                        jobRepository,
                        couponExpirationJob,
                        expirationClock,
                        new ExpirationJobRunRegistry(),
                        Runnable::run);

        // when
        service.submit("failed-batch-run", 2000);

        // then
        ExpirationJobRunSnapshot snapshot = service.find("failed-batch-run");
        assertThat(snapshot.status()).isEqualTo(ExpirationJobRunStatus.FAILED);
        assertThat(snapshot.failureReason()).isEqualTo("BATCH_STATUS_FAILED");
    }
}
