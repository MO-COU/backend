package com.mocou.lifecycle;

import org.springframework.stereotype.Component;

/** 만료 스케줄러의 현재 자동 실행 상태를 보관한다. */
@Component
public class ExpirationSchedulerState {

    // API 요청 스레드와 스케줄러 스레드가 함께 읽고 바꾸므로 접근을 동기화한다.
    private boolean enabled;

    public ExpirationSchedulerState(ExpirationSchedulerProperties properties) {
        // 서버를 다시 시작하면 application.yml에 선언한 시작 상태로 복원한다.
        this.enabled = properties.isSchedulerEnabled();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 자동 실행이 허용된 경우에만 Job 시작 요청을 수행한다.
     *
     * <p>상태 확인과 시작 요청을 같은 동기화 구간으로 묶어 OFF 응답 이후 새 자동 실행이 시작되는 경합을 막는다.
     */
    public synchronized void runIfEnabled(SchedulerAction action) throws Exception {
        if (!enabled) {
            return;
        }
        action.run();
    }

    @FunctionalInterface
    public interface SchedulerAction {

        void run() throws Exception;
    }
}
