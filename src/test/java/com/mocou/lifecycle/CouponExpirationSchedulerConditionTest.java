package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CouponExpirationSchedulerConditionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulerConfiguration.class);

    @Test
    @DisplayName("자동 스케줄을 꺼도 만료 스케줄러를 등록한다")
    void registersSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("mocou.lifecycle.expiration.scheduler-enabled=false")
                .run(context -> assertThat(context).hasSingleBean(CouponExpirationScheduler.class));
    }

    @Test
    @DisplayName("자동 스케줄을 켜면 만료 스케줄러를 등록한다")
    void registersSchedulerWhenEnabled() {
        contextRunner
                .withPropertyValues("mocou.lifecycle.expiration.scheduler-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CouponExpirationScheduler.class));
    }

    @Test
    @DisplayName("설정값 false로 런타임 자동 실행 상태를 초기화한다")
    void initializesRuntimeStateFromConfiguredValue() {
        contextRunner
                .withPropertyValues("mocou.lifecycle.expiration.scheduler-enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ExpirationSchedulerState.class);
                            assertThat(context.getBean(ExpirationSchedulerState.class).isEnabled())
                                    .isFalse();
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({CouponExpirationScheduler.class, ExpirationSchedulerState.class})
    @EnableConfigurationProperties(ExpirationSchedulerProperties.class)
    static class SchedulerConfiguration {

        @Bean
        CouponExpirationJobLauncher couponExpirationJobLauncher() {
            return mock(CouponExpirationJobLauncher.class);
        }
    }
}
