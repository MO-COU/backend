package com.mocou.lifecycle;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationSchedulerTest {

    @Mock private CouponExpirationJobLauncher jobLauncher;

    private ExpirationSchedulerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ExpirationSchedulerProperties();
    }

    @Test
    @DisplayName("스케줄 시점마다 만료 작업 실행을 요청한다")
    void requestsExpirationJobLaunchAtScheduledTime() throws Exception {
        properties.setSchedulerEnabled(true);
        CouponExpirationScheduler scheduler =
                new CouponExpirationScheduler(jobLauncher, new ExpirationSchedulerState(properties));

        // when
        scheduler.run();

        // then
        verify(jobLauncher).launchOrRestart();
    }

    @Test
    @DisplayName("자동 실행이 꺼져 있으면 만료 작업 실행을 요청하지 않는다")
    void doesNotRequestExpirationJobLaunchWhenDisabled() throws Exception {
        properties.setSchedulerEnabled(false);
        CouponExpirationScheduler scheduler =
                new CouponExpirationScheduler(jobLauncher, new ExpirationSchedulerState(properties));

        // when
        scheduler.run();

        // then
        verifyNoInteractions(jobLauncher);
    }
}
