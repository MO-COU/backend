package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
        given(chunkContext.getStepContext()).willReturn(stepContext);
        given(stepContext.getStepExecution()).willReturn(stepExecution);
        given(stepExecution.getJobParameters())
                .willReturn(
                        new JobParametersBuilder()
                                .addLocalDateTime("cutoffAt", CUTOFF_AT)
                                .toJobParameters());
    }

    @Test
    @DisplayName("전체 청크가 선택되면 다음 청크를 계속 처리한다")
    void continuesWhenAFullChunkWasSelected() throws Exception {
        // given
        givenCutoffAt();
        given(service.expireDueIssues(CUTOFF_AT, 1000)).willReturn(1000);

        // when
        RepeatStatus status = tasklet.execute(null, chunkContext);

        // then
        assertThat(status).isEqualTo(RepeatStatus.CONTINUABLE);
        verify(service).expireDueIssues(CUTOFF_AT, 1000);
    }

    @Test
    @DisplayName("전체 청크보다 적게 선택되면 처리를 종료한다")
    void finishesWhenLessThanAFullChunkWasSelected() throws Exception {
        // given
        givenCutoffAt();
        given(service.expireDueIssues(CUTOFF_AT, 1000)).willReturn(3);

        // when
        RepeatStatus status = tasklet.execute(null, chunkContext);

        // then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("JobParameter 청크 크기가 있으면 기본 설정보다 우선한다")
    void usesChunkSizeFromJobParameterWhenProvided() throws Exception {
        // given
        given(chunkContext.getStepContext()).willReturn(stepContext);
        given(stepContext.getStepExecution()).willReturn(stepExecution);
        given(stepExecution.getJobParameters())
                .willReturn(
                        new JobParametersBuilder()
                                .addLocalDateTime("cutoffAt", CUTOFF_AT)
                                .addLong("chunkSize", 2000L)
                                .toJobParameters());
        given(service.expireDueIssues(CUTOFF_AT, 2000)).willReturn(3);

        // when
        tasklet.execute(null, chunkContext);

        // then
        verify(service).expireDueIssues(CUTOFF_AT, 2000);
    }

    @Test
    @DisplayName("JobParameter 쿠폰 ID가 있으면 해당 쿠폰 범위로 만료 처리한다")
    void scopesExpirationToCouponIdWhenProvided() throws Exception {
        // given
        given(chunkContext.getStepContext()).willReturn(stepContext);
        given(stepContext.getStepExecution()).willReturn(stepExecution);
        given(stepExecution.getJobParameters())
                .willReturn(
                        new JobParametersBuilder()
                                .addLocalDateTime("cutoffAt", CUTOFF_AT)
                                .addLong("couponId", 2001L)
                                .toJobParameters());
        given(service.expireDueIssues(CUTOFF_AT, 1000, 2001L)).willReturn(3);

        // when
        tasklet.execute(null, chunkContext);

        // then
        verify(service).expireDueIssues(CUTOFF_AT, 1000, 2001L);
    }

    @Test
    @DisplayName("청크 크기가 0 이하이면 작업을 시작하지 않는다")
    void rejectsNonPositiveChunkSizeBeforeStartingWork() {
        // given
        CouponExpirationBatchProperties properties = new CouponExpirationBatchProperties();
        properties.setChunkSize(0);
        CouponExpirationTasklet invalidTasklet = new CouponExpirationTasklet(service, properties);
        givenCutoffAt();

        // when, then
        assertThatThrownBy(() -> invalidTasklet.execute(null, chunkContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunkSize must be positive");
    }
}
