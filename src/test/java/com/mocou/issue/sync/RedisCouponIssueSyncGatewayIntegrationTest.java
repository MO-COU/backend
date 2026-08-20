package com.mocou.issue.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;

class RedisCouponIssueSyncGatewayIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Test
    @DisplayName("Stream이 없어도 Consumer Group과 Stream을 함께 생성한다")
    void createsGroupAndStreamWhenMissing() {
        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

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
        redisTemplate.opsForStream().add(
                issueStreamKey(),
                Map.of("eventId", "existing-event"));

        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.CREATED);
        assertThat(groupNames()).containsExactly(
                RedisCouponIssueSyncGateway.GROUP_NAME);
    }

    @Test
    @DisplayName("이미 그룹이 존재하면 예외 없이 재사용한다")
    void reusesExistingGroup() {
        gateway.ensureConsumerGroup(COUPON_ID);

        CouponIssueSyncGroupResult result =
                gateway.ensureConsumerGroup(COUPON_ID);

        assertThat(result)
                .isEqualTo(CouponIssueSyncGroupResult.ALREADY_EXISTS);
        assertThat(groupNames()).containsExactly(
                RedisCouponIssueSyncGateway.GROUP_NAME);
    }

    private List<String> groupNames() {
        return redisTemplate.opsForStream()
                .groups(issueStreamKey())
                .stream()
                .map(StreamInfo.XInfoGroup::groupName)
                .toList();
    }
}
