package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
class CouponExpirationSchedulerTest {

    private static final LocalDateTime DATABASE_TIME = LocalDateTime.of(2026, 8, 18, 18, 0);

    @Mock private JobOperator jobOperator;
    @Mock private JobRepository jobRepository;
    @Mock private Job couponExpirationJob;
    @Mock private CouponExpirationRepository repository;
    @Mock private JobExecution runningExecution;
    @Mock private JobInstance jobInstance;
    @Mock private JobExecution failedExecution;

    private CouponExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler =
                new CouponExpirationScheduler(
                        jobOperator, jobRepository, couponExpirationJob, repository);
    }

    @Test
    void skipsTickWhenExpirationJobIsAlreadyRunning() throws Exception {
        when(jobRepository.findRunningJobExecutions("couponExpirationJob"))
                .thenReturn(Set.of(runningExecution));

        scheduler.run();

        verify(repository, never()).currentDatabaseTime();
        verify(jobOperator, never()).start(couponExpirationJob, null);
    }

    @Test
    void startsJobWithDatabaseTimeAsFixedCutoffAt() throws Exception {
        when(jobRepository.findRunningJobExecutions("couponExpirationJob")).thenReturn(Set.of());
        when(jobRepository.getJobInstances("couponExpirationJob", 0, 1)).thenReturn(List.of());
        when(repository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);

        scheduler.run();

        verify(jobOperator).start(eq(couponExpirationJob), parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getLocalDateTime("cutoffAt")).isEqualTo(DATABASE_TIME);
    }

    @Test
    void restartsLatestFailedExecutionBeforeStartingNewCutoff() throws Exception {
        when(jobRepository.findRunningJobExecutions("couponExpirationJob")).thenReturn(Set.of());
        when(jobRepository.getJobInstances("couponExpirationJob", 0, 1))
                .thenReturn(List.of(jobInstance));
        when(jobRepository.getLastJobExecution(jobInstance)).thenReturn(failedExecution);
        when(failedExecution.getStatus()).thenReturn(BatchStatus.FAILED);

        scheduler.run();

        verify(jobOperator).restart(failedExecution);
        verify(repository, never()).currentDatabaseTime();
    }
}
