package com.mocou.issue;

import org.springframework.data.redis.core.RedisOperations;

@FunctionalInterface
public interface CouponIssueReplicationWaiter {

    long waitForReplication(
            RedisOperations<String, String> operations,
            int requiredReplicas,
            long timeoutMs
    );
}
