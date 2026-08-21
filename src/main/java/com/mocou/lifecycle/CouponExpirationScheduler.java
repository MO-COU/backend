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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
// 기본값은 application.yml에서 관리한다. 실행 옵션은 이 값을 일시적으로 덮어쓸 수 있다.
@ConditionalOnProperty(
        prefix = "mocou.lifecycle.expiration",
        name = "scheduler-enabled",
        havingValue = "true")
public class CouponExpirationScheduler {

    private static final String JOB_NAME = "couponExpirationJob";

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job couponExpirationJob;
    private final CouponExpirationRepository repository;

    public CouponExpirationScheduler(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(JOB_NAME) Job couponExpirationJob,
            CouponExpirationRepository repository) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.couponExpirationJob = couponExpirationJob;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${mocou.lifecycle.expiration.fixed-delay-ms}")
    public void run() throws Exception {
        if (!jobRepository.findRunningJobExecutions(JOB_NAME).isEmpty()) {
            return;
        }

        JobExecution failedExecution = latestFailedExecution();
        if (failedExecution != null) {
            jobOperator.restart(failedExecution);
            return;
        }

        LocalDateTime cutoffAt = repository.currentDatabaseTime();
        JobParameters parameters =
                new JobParametersBuilder().addLocalDateTime("cutoffAt", cutoffAt).toJobParameters();
        try {
            jobOperator.start(couponExpirationJob, parameters);
        } catch (JobExecutionAlreadyRunningException ignored) {
            // 동일 애플리케이션에서 겹친 스케줄 실행은 다음 주기에 다시 확인한다.
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
