package com.mocou.lifecycle;

import java.time.LocalDateTime;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
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
        if (properties.getChunkSize() <= 0) {
            throw new IllegalStateException("chunkSize must be positive");
        }

        LocalDateTime cutoffAt =
                chunkContext
                        .getStepContext()
                        .getStepExecution()
                        .getJobParameters()
                        .getLocalDateTime("cutoffAt");
        if (cutoffAt == null) {
            throw new IllegalArgumentException("cutoffAt job parameter is required");
        }

        int selectedCount = service.expireDueIssues(cutoffAt, properties.getChunkSize());
        return RepeatStatus.continueIf(selectedCount == properties.getChunkSize());
    }
}
