package com.mocou.issue;

import java.util.concurrent.CompletionException;

import io.lettuce.core.api.async.BaseRedisAsyncCommands;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.stereotype.Component;

@Component
public class LettuceCouponIssueReplicationWaiter
        implements CouponIssueReplicationWaiter {

    @Override
    public long waitForReplication(
            RedisOperations<String, String> operations,
            int requiredReplicas,
            long timeoutMs
    ) {
        try {
            Long acknowledgedReplicas = operations.execute(
                    (RedisCallback<Long>) connection -> {
                        @SuppressWarnings("unchecked")
                        BaseRedisAsyncCommands<byte[], byte[]> nativeCommands =
                                (BaseRedisAsyncCommands<byte[], byte[]>)
                                        connection.getNativeConnection();

                        return nativeCommands.waitForReplication(
                                        requiredReplicas,
                                        timeoutMs)
                                .toCompletableFuture()
                                .join();
                    });

            return acknowledgedReplicas == null
                    ? 0L
                    : acknowledgedReplicas;
        } catch (CompletionException exception) {
            throw new RedisConnectionFailureException(
                    "Redis Replica ACK 확인에 실패했습니다.",
                    exception);
        }
    }
}
