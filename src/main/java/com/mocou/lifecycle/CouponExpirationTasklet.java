package com.mocou.lifecycle;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
@Slf4j
/** 고정된 만료 기준 시각으로 청크 단위 처리를 반복한다. */
public class CouponExpirationTasklet implements Tasklet {

    private final CouponExpirationService service;
    private final CouponExpirationBatchProperties properties;

    public CouponExpirationTasklet(
            CouponExpirationService service, CouponExpirationBatchProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var stepExecution = chunkContext.getStepContext().getStepExecution();
        JobParameters jobParameters = stepExecution.getJobParameters();
        int chunkSize = resolveChunkSize(jobParameters);
        LocalDateTime cutoffAt = jobParameters.getLocalDateTime("cutoffAt");
        if (cutoffAt == null) {
            throw new IllegalArgumentException("cutoffAt job parameter is required");
        }

        long startedAt = System.nanoTime();
        int selectedCount = service.expireDueIssues(cutoffAt, chunkSize);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        recordChunkResult(stepExecution.getExecutionContext(), jobParameters, selectedCount, durationMs);
        return RepeatStatus.continueIf(selectedCount == chunkSize);
    }

    private int resolveChunkSize(JobParameters jobParameters) {
        Long parameterChunkSize = jobParameters.getLong("chunkSize");
        long resolvedChunkSize = parameterChunkSize != null ? parameterChunkSize : properties.getChunkSize();
        if (resolvedChunkSize <= 0 || resolvedChunkSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("chunkSize must be positive");
        }
        return (int) resolvedChunkSize;
    }

    private void recordChunkResult(
            ExecutionContext executionContext,
            JobParameters jobParameters,
            int selectedCount,
            long durationMs) {
        if (executionContext == null) {
            return;
        }
        int sequence = executionContext.getInt("expiration.chunk.count", 0) + 1;
        executionContext.putInt("expiration.chunk.count", sequence);
        executionContext.putInt("expiration.chunk." + sequence + ".selectedCount", selectedCount);
        executionContext.putLong("expiration.chunk." + sequence + ".durationMs", durationMs);
        log.info(
                "coupon expiration chunk completed: runKey={}, sequence={}, selectedCount={}, durationMs={}",
                jobParameters.getString("runKey"),
                sequence,
                selectedCount,
                durationMs);
    }
}
