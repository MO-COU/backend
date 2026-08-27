package com.mocou.lifecycle;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 설정된 주기마다 만료 Job 실행을 요청한다. */
@Component
public class CouponExpirationScheduler {

    private final CouponExpirationJobLauncher jobLauncher;
    private final ExpirationSchedulerState schedulerState;

    public CouponExpirationScheduler(
            CouponExpirationJobLauncher jobLauncher, ExpirationSchedulerState schedulerState) {
        this.jobLauncher = jobLauncher;
        this.schedulerState = schedulerState;
    }

    @Scheduled(fixedDelayString = "${mocou.lifecycle.expiration.fixed-delay-ms}")
    public void run() throws Exception {
        // OFF 상태에서는 이미 실행 중인 Job을 취소하지 않고, 새 자동 실행 요청만 건너뛴다.
        schedulerState.runIfEnabled(jobLauncher::launchOrRestart);
    }
}
