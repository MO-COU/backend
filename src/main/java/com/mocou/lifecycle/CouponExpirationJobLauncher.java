package com.mocou.lifecycle;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 만료 Job의 중복 실행 방지, 실패 재시작, 새 실행 시작 정책을 담당한다. */
@Component
public class CouponExpirationJobLauncher {

    private static final String JOB_NAME = "couponExpirationJob";

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job couponExpirationJob;
    private final ExpirationClock expirationClock;

    public CouponExpirationJobLauncher(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(JOB_NAME) Job couponExpirationJob,
            ExpirationClock expirationClock) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.couponExpirationJob = couponExpirationJob;
        this.expirationClock = expirationClock;
    }

    public void launchOrRestart() throws Exception {
        if (!jobRepository.findRunningJobExecutions(JOB_NAME).isEmpty()) {
            return;
        }

        JobExecution failedExecution = latestFailedExecution();
        if (failedExecution != null) {
            jobOperator.restart(failedExecution);
            return;
        }

        LocalDateTime cutoffAt = expirationClock.now();
        JobParameters parameters =
                new JobParametersBuilder().addLocalDateTime("cutoffAt", cutoffAt).toJobParameters();
        try {
            jobOperator.start(couponExpirationJob, parameters);
        } catch (JobExecutionAlreadyRunningException ignored) {
            // 두 스케줄 실행이 겹치면 다음 주기에 다시 확인한다.
        }
    }

    private JobExecution latestFailedExecution() {
        List<JobInstance> instances = jobRepository.getJobInstances(JOB_NAME, 0, 1);
        if (instances.isEmpty()) {
            return null;
        }

        JobExecution execution = jobRepository.getLastJobExecution(instances.getFirst());
        return execution != null && execution.getStatus() == BatchStatus.FAILED ? execution : null;
    }
}
