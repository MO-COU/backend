package com.mocou.lifecycle.perf;

import com.mocou.lifecycle.ExpirationClock;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

/** perf 프로필에서만 만료 Job을 한 번 비동기로 실행한다. */
public class ExpirationJobControlService {

    private static final String JOB_NAME = "couponExpirationJob";

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job couponExpirationJob;
    private final ExpirationClock expirationClock;
    private final ExpirationJobRunRegistry registry;
    private final Executor executor;

    public ExpirationJobControlService(
            JobOperator jobOperator,
            JobRepository jobRepository,
            Job couponExpirationJob,
            ExpirationClock expirationClock,
            ExpirationJobRunRegistry registry,
            Executor executor) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.couponExpirationJob = couponExpirationJob;
        this.expirationClock = expirationClock;
        this.registry = registry;
        this.executor = executor;
    }

    public ExpirationJobRunSnapshot submit(String runKey, int chunkSize) {
        if (!jobRepository.findRunningJobExecutions(JOB_NAME).isEmpty()) {
            throw new IllegalStateException("JOB_ALREADY_RUNNING");
        }
        ExpirationJobReservation reservation = registry.reserve(runKey, chunkSize);
        if (reservation.status() != ExpirationJobReservationStatus.ACCEPTED) {
            throw new IllegalStateException(reservation.status().name());
        }
        try {
            executor.execute(() -> run(runKey, chunkSize));
        } catch (RejectedExecutionException exception) {
            registry.fail(runKey, LocalDateTime.now(), "EXECUTOR_UNAVAILABLE");
            throw new IllegalStateException("EXECUTOR_UNAVAILABLE", exception);
        }
        return registry.find(runKey);
    }

    public ExpirationJobRunSnapshot find(String runKey) {
        return registry.find(runKey);
    }

    private void run(String runKey, int chunkSize) {
        try {
            LocalDateTime cutoffAt = expirationClock.now();
            LocalDateTime startedAt = LocalDateTime.now();
            registry.markRunning(runKey, cutoffAt, startedAt);
            JobParameters parameters =
                    new JobParametersBuilder()
                            .addString("runKey", runKey)
                            .addLong("chunkSize", (long) chunkSize)
                            .addLocalDateTime("cutoffAt", cutoffAt)
                            .toJobParameters();
            JobExecution execution = jobOperator.start(couponExpirationJob, parameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                registry.fail(
                        runKey,
                        LocalDateTime.now(),
                        "BATCH_STATUS_" + execution.getStatus());
                return;
            }
            registry.complete(runKey, execution.getId(), LocalDateTime.now(), chunkResults(execution));
        } catch (Exception exception) {
            registry.fail(runKey, LocalDateTime.now(), "JOB_EXECUTION_FAILED");
        }
    }

    private List<ExpirationJobChunkResult> chunkResults(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .flatMap(step -> {
                    int count = step.getExecutionContext().getInt("expiration.chunk.count", 0);
                    return java.util.stream.IntStream.rangeClosed(1, count)
                            .mapToObj(sequence -> new ExpirationJobChunkResult(sequence,
                                    step.getExecutionContext().getInt("expiration.chunk." + sequence + ".selectedCount"),
                                    step.getExecutionContext().getLong("expiration.chunk." + sequence + ".durationMs")));
                })
                .toList();
    }
}
