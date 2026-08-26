package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpirationSchedulerStateTest {

    @Test
    @DisplayName("Job 시작 요청 중에도 자동 실행 상태를 즉시 끈다")
    void disablesSchedulerWhileLaunchRequestIsRunning() throws Exception {
        ExpirationSchedulerProperties properties = new ExpirationSchedulerProperties();
        properties.setSchedulerEnabled(true);
        ExpirationSchedulerState state = new ExpirationSchedulerState(properties);
        CountDownLatch launchStarted = new CountDownLatch(1);
        CountDownLatch allowLaunchToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> launch =
                    executor.submit(
                            () -> {
                                state.runIfEnabled(
                                        () -> {
                                            launchStarted.countDown();
                                            allowLaunchToFinish.await();
                                        });
                                return null;
                            });
            assertThat(launchStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> disable = executor.submit(() -> state.setEnabled(false));

            assertThat(disable.get(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(state.isEnabled()).isFalse();

            allowLaunchToFinish.countDown();
            launch.get(1, TimeUnit.SECONDS);
        } finally {
            allowLaunchToFinish.countDown();
            executor.shutdownNow();
        }
    }
}
