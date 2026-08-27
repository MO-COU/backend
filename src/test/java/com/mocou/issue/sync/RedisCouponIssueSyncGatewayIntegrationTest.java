package com.mocou.issue.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;

import static org.assertj.core.api.Assertions.assertThat;

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
