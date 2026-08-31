package com.mocou.issue;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mocou.issue.replication")
public class CouponIssueReplicationProperties {

    private boolean waitEnabled;

    @Min(1)
    private int requiredReplicas = 1;

    @Min(1)
    private long timeoutMs = 100;

    public boolean isWaitEnabled() {
        return waitEnabled;
    }

    public void setWaitEnabled(boolean waitEnabled) {
        this.waitEnabled = waitEnabled;
    }

    public int getRequiredReplicas() {
        return requiredReplicas;
    }

    public void setRequiredReplicas(int requiredReplicas) {
        this.requiredReplicas = requiredReplicas;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
