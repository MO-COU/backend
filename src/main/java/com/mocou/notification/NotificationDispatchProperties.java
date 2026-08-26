package com.mocou.notification;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** outbox: NotificationDispatchConsumer의 폴링 배치 크기/간격/재시도 한도 설정. */
@Validated
@ConfigurationProperties(prefix = "mocou.notification.dispatch")
public class NotificationDispatchProperties {

    @Min(1)
    private long pollIntervalMs = 100;

    @Min(1)
    private int batchSize = 50;

    // 이 값을 넘겨 재시도해도 계속 실패하면 더 이상 재시도하지 않고
    // notification.status를 FAILED로 확정한다.
    @Min(1)
    private int maxDeliveryCount = 5;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxDeliveryCount() {
        return maxDeliveryCount;
    }

    public void setMaxDeliveryCount(int maxDeliveryCount) {
        this.maxDeliveryCount = maxDeliveryCount;
    }
}
