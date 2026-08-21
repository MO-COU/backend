package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CouponExpirationSchedulerConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulerConfiguration.class);

    @Test
    @DisplayName("자동 스케줄을 끄면 만료 스케줄러를 등록하지 않는다")
    void doesNotRegisterSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("mocou.lifecycle.expiration.scheduler-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CouponExpirationScheduler.class));
    }

    @Test
    @DisplayName("자동 스케줄을 켜면 만료 스케줄러를 등록한다")
    void registersSchedulerWhenEnabled() {
        contextRunner
                .withPropertyValues("mocou.lifecycle.expiration.scheduler-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CouponExpirationScheduler.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CouponExpirationScheduler.class)
    static class SchedulerConfiguration {

        @Bean
        JobOperator jobOperator() {
            return mock(JobOperator.class);
        }

        @Bean
        JobRepository jobRepository() {
            return mock(JobRepository.class);
        }

        @Bean(name = "couponExpirationJob")
        Job couponExpirationJob() {
            return mock(Job.class);
        }

        @Bean
        CouponExpirationRepository couponExpirationRepository() {
            return mock(CouponExpirationRepository.class);
        }
    }
}
