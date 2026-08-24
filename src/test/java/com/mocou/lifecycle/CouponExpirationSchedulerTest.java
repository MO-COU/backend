package com.mocou.lifecycle;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationSchedulerTest {

    @Mock private CouponExpirationJobLauncher jobLauncher;

    private CouponExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CouponExpirationScheduler(jobLauncher);
    }

    @Test
    @DisplayName("스케줄 시점마다 만료 작업 실행을 요청한다")
    void requestsExpirationJobLaunchAtScheduledTime() throws Exception {
        // when
        scheduler.run();

        // then
        verify(jobLauncher).launchOrRestart();
    }
}
