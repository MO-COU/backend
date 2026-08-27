package com.mocou.loadtest;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

@EnableAsync
@Configuration
@EnableConfigurationProperties(LoadTestSsmProperties.class)
public class LoadTestSsmConfig {

    @Bean
    SsmClient ssmClient(LoadTestSsmProperties properties) {
        return SsmClient.builder().region(Region.of(properties.region())).build();
    }

    @Bean(name = "loadTestExecutor")
    Executor loadTestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("load-test-ssm-");
        executor.initialize();
        return executor;
    }
}
