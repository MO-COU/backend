package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

@ExtendWith(MockitoExtension.class)
class CouponExpirationJobLauncherTest {

    private static final LocalDateTime DATABASE_TIME = LocalDateTime.of(2026, 8, 18, 18, 0);

    @Mock private JobOperator jobOperator;
    @Mock private JobRepository jobRepository;
    @Mock private Job couponExpirationJob;
    @Mock private ExpirationClock expirationClock;
    @Mock private JobExecution runningExecution;
    @Mock private JobInstance jobInstance;
    @Mock private JobExecution failedExecution;

    private CouponExpirationJobLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher =
                new CouponExpirationJobLauncher(
                        jobOperator, jobRepository, couponExpirationJob, expirationClock);
    }

    @Test
    @DisplayName("만료 작업이 실행 중이면 새 실행을 건너뛴다")
    void skipsLaunchWhenExpirationJobIsAlreadyRunning() throws Exception {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob"))
                .willReturn(Set.of(runningExecution));

        // when
        launcher.launchOrRestart();

        // then
        verify(expirationClock, never()).now();
        verify(jobOperator, never()).start(couponExpirationJob, null);
    }

    @Test
    @DisplayName("DB 시각을 고정 만료 기준 시각으로 새 작업을 시작한다")
    void startsJobWithDatabaseTimeAsFixedCutoffAt() throws Exception {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob")).willReturn(Set.of());
        given(jobRepository.getJobInstances("couponExpirationJob", 0, 1)).willReturn(List.of());
        given(expirationClock.now()).willReturn(DATABASE_TIME);
        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);

        // when
        launcher.launchOrRestart();

        // then
        verify(jobOperator).start(eq(couponExpirationJob), parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getLocalDateTime("cutoffAt")).isEqualTo(DATABASE_TIME);
    }

    @Test
    @DisplayName("마지막 작업이 실패했으면 새 작업 대신 재시작한다")
    void restartsLatestFailedExecutionBeforeStartingNewCutoff() throws Exception {
        // given
        given(jobRepository.findRunningJobExecutions("couponExpirationJob")).willReturn(Set.of());
        given(jobRepository.getJobInstances("couponExpirationJob", 0, 1))
                .willReturn(List.of(jobInstance));
        given(jobRepository.getLastJobExecution(jobInstance)).willReturn(failedExecution);
        given(failedExecution.getStatus()).willReturn(BatchStatus.FAILED);

        // when
        launcher.launchOrRestart();

        // then
        verify(jobOperator).restart(failedExecution);
        verify(expirationClock, never()).now();
    }
}
