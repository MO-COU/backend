package com.mocou.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 설정된 주기마다 만료 Job 실행을 요청한다. */
@Component
// 기본값은 application.yml에서 관리한다. 실행 옵션은 이 값을 일시적으로 덮어쓸 수 있다.
@ConditionalOnProperty(
        prefix = "mocou.lifecycle.expiration",
        name = "scheduler-enabled",
        havingValue = "true")
public class CouponExpirationScheduler {

    private final CouponExpirationJobLauncher jobLauncher;

    public CouponExpirationScheduler(CouponExpirationJobLauncher jobLauncher) {
        this.jobLauncher = jobLauncher;
    }

    @Scheduled(fixedDelayString = "${mocou.lifecycle.expiration.fixed-delay-ms}")
    public void run() throws Exception {
        jobLauncher.launchOrRestart();
    }
}
