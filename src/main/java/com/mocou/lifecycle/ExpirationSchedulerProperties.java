package com.mocou.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 만료 스케줄러의 애플리케이션 시작 시 초기 상태를 제공한다. */
@ConfigurationProperties(prefix = "mocou.lifecycle.expiration")
public class ExpirationSchedulerProperties {

    // 이 값은 런타임 상태의 시작값일 뿐, 실행 중 API 변경을 다시 덮어쓰지 않는다.
    private boolean schedulerEnabled;

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}
