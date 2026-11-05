package com.mocou.issue.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCouponIssueSyncGatewayIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Test
    @DisplayName("Stream이 없어도 Consumer Group과 Stream을 함께 생성한다")
    void createsGroupAndStreamWhenMissing() {
        // given
        // when
        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

        // then
        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.CREATED);
        assertThat(redisTemplate.hasKey(issueStreamKey()))
                .isTrue();
        assertThat(groupNames()).containsExactly(
                RedisCouponIssueSyncGateway.GROUP_NAME);
    }

    @Test
    @DisplayName("이미 존재하는 Stream에도 Consumer Group을 생성한다")
    void createsGroupOnExistingStream() {
        // given
        redisTemplate.opsForStream().add(
                issueStreamKey(),
                Map.of("eventId", "existing-event"));

        // when
        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

        // then
        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.CREATED);
        assertThat(groupNames()).containsExactly(
                RedisCouponIssueSyncGateway.GROUP_NAME);
    }

    @Test
    @DisplayName("이미 그룹이 존재하면 예외 없이 재사용한다")
    void reusesExistingGroup() {
        // given
        gateway.ensureConsumerGroup(COUPON_ID);

        // when
        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

        // then
        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.ALREADY_EXISTS);
        assertThat(groupNames()).containsExactly(
                RedisCouponIssueSyncGateway.GROUP_NAME);
    }

    @Test
    @DisplayName("DLQ Stream이 없어도 복구용 Consumer Group과 Stream을 함께 생성한다")
    void createsDlqGroupAndStreamWhenMissing() {
        // given
        // when
        CouponIssueSyncGroupResult result =
                gateway.ensureDlqConsumerGroup(COUPON_ID);

        // then
        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.CREATED);
        assertThat(redisTemplate.hasKey(dlqStreamKey()))
                .isTrue();
        assertThat(dlqGroupNames()).containsExactly(
                RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
    }

    @Test
    @DisplayName("DLQ 그룹이 이미 존재하면 예외 없이 재사용한다")
    void reusesExistingDlqGroup() {
        // given
        gateway.ensureDlqConsumerGroup(COUPON_ID);

        // when
        CouponIssueSyncGroupResult result =
                gateway.ensureDlqConsumerGroup(COUPON_ID);

        // then
        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.ALREADY_EXISTS);
        assertThat(dlqGroupNames()).containsExactly(
                RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
    }

    /** 메인 그룹과 DLQ 그룹이 서로 다른 스트림에 독립적으로 생기는지 확인한다. */
    @Test
    @DisplayName("메인 그룹과 DLQ 그룹은 서로 다른 스트림에 독립적으로 생긴다")
    void mainAndDlqGroupsAreIndependent() {
        // when
        gateway.ensureConsumerGroup(COUPON_ID);
        gateway.ensureDlqConsumerGroup(COUPON_ID);

        // then
        assertThat(groupNames()).containsExactly(RedisCouponIssueSyncGateway.GROUP_NAME);
        assertThat(dlqGroupNames()).containsExactly(RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
    }

    @Test
    @DisplayName("Stream이 없으면 미확인 건수는 0이다")
    void pendingCountIsZeroWhenStreamMissing() {
        // when
        long pending = gateway.pendingCount(
                issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);

        // then
        assertThat(pending).isZero();
    }

    @Test
    @DisplayName("Consumer Group이 없으면 미확인 건수는 0이다")
    void pendingCountIsZeroWhenGroupMissing() {
        // given
        redisTemplate.opsForStream().add(
                issueStreamKey(),
                Map.of("eventId", "existing-event"));

        // when
        long pending = gateway.pendingCount(
                issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);

        // then
        assertThat(pending).isZero();
    }

    @Test
    @DisplayName("그룹이 읽고 ACK하지 않은 이벤트 수를 센다")
    void countsUnacknowledgedEvents() {
        // given
        redisTemplate.opsForStream().add(issueStreamKey(), Map.of("eventId", "event-1"));
        redisTemplate.opsForStream().add(issueStreamKey(), Map.of("eventId", "event-2"));
        gateway.ensureConsumerGroup(COUPON_ID);
        redisTemplate.opsForStream().read(
                Consumer.from(RedisCouponIssueSyncGateway.GROUP_NAME, "test-consumer"),
                StreamOffset.create(issueStreamKey(), ReadOffset.lastConsumed()));

        // when
        long pending = gateway.pendingCount(
                issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);

        // then
        assertThat(pending).isEqualTo(2);
    }

    @Test
    @DisplayName("그룹이 없다는 오류가 아니면 0으로 바꾸지 않고 던진다")
    void rethrowsRedisErrorsOtherThanNoGroup() {
        // given
        redisTemplate.opsForValue().set(issueStreamKey(), "not-a-stream");

        // when, then
        assertThatThrownBy(() -> gateway.pendingCount(
                issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME))
                .isInstanceOf(RedisSystemException.class)
                .rootCause()
                .hasMessageContaining("WRONGTYPE");
    }

    private List<String> groupNames() {
        return redisTemplate.opsForStream()
                .groups(issueStreamKey())
                .stream()
                .map(StreamInfo.XInfoGroup::groupName)
                .toList();
    }

    private List<String> dlqGroupNames() {
        return redisTemplate.opsForStream()
                .groups(dlqStreamKey())
                .stream()
                .map(StreamInfo.XInfoGroup::groupName)
                .toList();
    }
}
