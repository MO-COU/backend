package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=always",
            "mocou.lifecycle.expiration.fixed-delay-ms=3600000"
        })
class CouponExpirationBatchTest extends CouponLifecycleIntegrationTestSupport {

    private static final long ISSUE_ID = 3001L;

    @Autowired private JobOperator jobOperator;

    @Autowired
    @Qualifier("couponExpirationJob")
    private Job couponExpirationJob;

    @Test
    @DisplayName("고정 만료 기준 시각으로 만료 배치를 실행한다")
    void runsCouponExpirationJobWithFixedCutoffAt() throws Exception {
        insertIssuedCoupon(ISSUE_ID);
        jdbcTemplate.update(
                "UPDATE coupon_issue SET expires_at = ? WHERE coupon_issue_id = ?",
                LocalDateTime.of(2026, 8, 18, 17, 0),
                ISSUE_ID);
        JobParameters parameters =
                new JobParametersBuilder()
                        .addLocalDateTime("cutoffAt", LocalDateTime.of(2026, 8, 18, 18, 0))
                        .toJobParameters();

        JobExecution execution = jobOperator.start(couponExpirationJob, parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM coupon_issue WHERE coupon_issue_id = ?",
                                String.class,
                                ISSUE_ID))
                .isEqualTo("EXPIRED");
    }
}
