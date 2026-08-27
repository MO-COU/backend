package com.mocou.lifecycle;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableBatchProcessing
@EnableScheduling
@EnableConfigurationProperties({CouponExpirationBatchProperties.class, ExpirationSchedulerProperties.class})
/** 만료 Job과 Step을 조립하고, 스케줄·설정 바인딩을 활성화한다. */
public class CouponExpirationBatchConfig {

    @Bean
    public Job couponExpirationJob(JobRepository jobRepository, Step couponExpirationStep) {
        return new JobBuilder("couponExpirationJob", jobRepository)
                .start(couponExpirationStep)
                .build();
    }

    @Bean
    public Step couponExpirationStep(
            JobRepository jobRepository, CouponExpirationTasklet couponExpirationTasklet) {
        return new StepBuilder("couponExpirationStep", jobRepository)
                .tasklet(couponExpirationTasklet)
                .build();
    }
}
