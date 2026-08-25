package com.mocou.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 만료 스케줄러의 현재 자동 실행 상태를 보관한다. */
@Component
public class ExpirationSchedulerState {

    // API 요청 스레드와 스케줄러 스레드가 즉시 같은 값을 보도록 원자 상태로 관리한다.
    private final AtomicBoolean enabled;

    public ExpirationSchedulerState(ExpirationSchedulerProperties properties) {
        // 서버를 다시 시작하면 application.yml에 선언한 시작 상태로 복원한다.
        this.enabled = new AtomicBoolean(properties.isSchedulerEnabled());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /**
     * 자동 실행이 허용된 경우에만 Job 시작 요청을 수행한다.
     *
     * <p>Job 실행 전체를 잠그지 않으므로 OFF 요청은 실행 중인 Job을 기다리지 않고 즉시 반영된다. 다만 OFF 요청 전에
     * 상태 확인을 통과한 스케줄 호출 1건은 시작 요청을 계속할 수 있다.
     */
    public void runIfEnabled(SchedulerAction action) throws Exception {
        if (!enabled.get()) {
            return;
        }
        action.run();
    }

    @FunctionalInterface
    public interface SchedulerAction {

        void run() throws Exception;
    }
}
