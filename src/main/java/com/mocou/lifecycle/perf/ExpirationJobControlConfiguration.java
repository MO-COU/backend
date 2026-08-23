package com.mocou.lifecycle.perf;

import com.mocou.lifecycle.ExpirationClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("perf")
@ConditionalOnExpression(
        "'${mocou.perf.expiration-control-enabled:false}' == 'true' "
                + "&& '${mocou.lifecycle.expiration.scheduler-enabled:true}' == 'false'")
public class ExpirationJobControlConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService expirationJobControlExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    @Bean
    ExpirationJobRunRegistry expirationJobRunRegistry() {
        return new ExpirationJobRunRegistry();
    }

    @Bean
    ExpirationJobControlService expirationJobControlService(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("couponExpirationJob") Job couponExpirationJob,
            ExpirationClock expirationClock,
            ExpirationJobRunRegistry registry,
            ExecutorService expirationJobControlExecutor) {
        return new ExpirationJobControlService(
                jobOperator,
                jobRepository,
                couponExpirationJob,
                expirationClock,
                registry,
                expirationJobControlExecutor);
    }
}
