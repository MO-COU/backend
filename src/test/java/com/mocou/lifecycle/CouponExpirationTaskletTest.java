package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
class CouponExpirationTaskletTest {

    private static final LocalDateTime CUTOFF_AT = LocalDateTime.of(2026, 8, 18, 18, 0);

    @Mock private CouponExpirationService service;
    @Mock private ChunkContext chunkContext;
    @Mock private StepContext stepContext;
    @Mock private StepExecution stepExecution;

    private CouponExpirationTasklet tasklet;

    @BeforeEach
    void setUp() {
        CouponExpirationBatchProperties properties = new CouponExpirationBatchProperties();
        properties.setChunkSize(1000);
        tasklet = new CouponExpirationTasklet(service, properties);
    }

    private void givenCutoffAt() {
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getJobParameters())
                .thenReturn(
                        new JobParametersBuilder()
                                .addLocalDateTime("cutoffAt", CUTOFF_AT)
                                .toJobParameters());
    }

    @Test
    void continuesWhenAFullChunkWasSelected() throws Exception {
        givenCutoffAt();
        when(service.expireDueIssues(CUTOFF_AT, 1000)).thenReturn(1000);

        RepeatStatus status = tasklet.execute(null, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.CONTINUABLE);
        verify(service).expireDueIssues(CUTOFF_AT, 1000);
    }

    @Test
    void finishesWhenLessThanAFullChunkWasSelected() throws Exception {
        givenCutoffAt();
        when(service.expireDueIssues(CUTOFF_AT, 1000)).thenReturn(3);

        RepeatStatus status = tasklet.execute(null, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void rejectsNonPositiveChunkSizeBeforeStartingWork() {
        CouponExpirationBatchProperties properties = new CouponExpirationBatchProperties();
        properties.setChunkSize(0);
        CouponExpirationTasklet invalidTasklet = new CouponExpirationTasklet(service, properties);

        assertThatThrownBy(() -> invalidTasklet.execute(null, chunkContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunkSize must be positive");
    }
}
