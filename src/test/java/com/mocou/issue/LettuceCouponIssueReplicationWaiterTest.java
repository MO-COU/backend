package com.mocou.issue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;

@ExtendWith(MockitoExtension.class)
class LettuceCouponIssueReplicationWaiterTest {

    @Mock
    private RedisOperations<String, String> operations;

    @Test
    @DisplayName("WAIT 비동기 실행 예외를 Redis 연결 실패 예외로 변환한다")
    @SuppressWarnings("unchecked")
    void convertsCompletionExceptionToRedisConnectionFailure() {
        given(operations.execute(any(RedisCallback.class)))
                .willThrow(new CompletionException(
                        new IllegalStateException("connection closed")));

        LettuceCouponIssueReplicationWaiter waiter =
                new LettuceCouponIssueReplicationWaiter();

        assertThatThrownBy(() ->
                waiter.waitForReplication(operations, 1, 100L))
                .isInstanceOf(RedisConnectionFailureException.class)
                .hasMessage("Redis Replica ACK 확인에 실패했습니다.")
                .hasCauseInstanceOf(CompletionException.class);
    }
}
