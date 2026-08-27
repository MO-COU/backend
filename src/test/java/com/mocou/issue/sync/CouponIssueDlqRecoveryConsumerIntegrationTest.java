package com.mocou.issue.sync;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import com.mocou.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CouponIssueSyncConsumer}가 재시도 한도를 넘겨 DLQ로 옮긴 이벤트를
 * {@link CouponIssueDlqRecoveryConsumer}가 다시 처리하는지 검증한다. DB 저장은
 * mock으로 대체하고 Redis 상태(DLQ 스트림/PEL, 재고, 예약 회원)만 실제로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueDlqRecoveryConsumerIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Mock
    private CouponIssueSyncRepository repository;

    private RedisStreamGroupRecovery streamGroupRecovery;
    private ActiveCouponIdHolder activeCouponIdHolder;

    @BeforeEach
    void setUpSharedComponents() {
        streamGroupRecovery = new RedisStreamGroupRecovery(redisTemplate);
        activeCouponIdHolder = new ActiveCouponIdHolder(repository);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
    }

    @Test
    @DisplayName("DLQ에 새로 들어온 이벤트를 저장 재시도하고 성공하면 DLQ에서 제거한다")
    void retriesAndSucceedsForFreshDlqEntry() {
        // given
        CouponIssueSyncProperties properties = new CouponIssueSyncProperties();
        addDlqEvent("event-1", 100L, 1L);
        CouponIssueDlqRecoveryConsumer consumer = newConsumer(properties);

        // when
        consumer.recover();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().memberId()).isEqualTo(100L);
        assertThat(dlqPendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(dlqStreamKey())).isZero();
    }

    @Test
    @DisplayName("이전 복구 시도가 ACK 못 한 채 방치된 DLQ 엔트리도 인수해 재시도한다")
    void retriesStalePendingDlqEntryFromCrashedRecovery() {
        // given
        CouponIssueSyncProperties properties = new CouponIssueSyncProperties();
        properties.setDlqPendingMinIdleMs(100);
        gateway.ensureDlqConsumerGroup(COUPON_ID);
        addDlqEvent("event-1", 100L, 1L);
        // "crashed-recovery-worker"가 읽어가기만 하고 ACK를 안 한 상황을 재현한다.
        redisTemplate.<String, String>opsForStream().read(
                Consumer.from(RedisCouponIssueSyncGateway.DLQ_GROUP_NAME, "crashed-recovery-worker"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(dlqStreamKey(), ReadOffset.lastConsumed()));
        awaitDlqPendingMinIdle();
        CouponIssueDlqRecoveryConsumer consumer = newConsumer(properties);

        // when
        consumer.recover();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(dlqPendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(dlqStreamKey())).isZero();
    }

    @Test
    @DisplayName("DLQ 복구마저 최종 한도를 넘기면 그때 보상하고 최종 실패로 기록한다")
    void finalizesAndCompensatesWhenDlqRetryLimitExceeded() {
        // given
        CouponIssueSyncProperties properties = new CouponIssueSyncProperties();
        properties.setDlqPendingMinIdleMs(100);
        properties.setDlqMaxDeliveryCount(3);
        // 원본 예약 상태(재고 차감 + 발급 회원 등록)를 재현해, 여기서 비로소
        // 보상되는지 끝까지 검증한다.
        setStock(5);
        redisTemplate.opsForSet().add(issuedMembersKey(), "100");
        gateway.ensureDlqConsumerGroup(COUPON_ID);
        addDlqEvent("event-1", 100L, 1L);
        RecordId recordId = redisTemplate.<String, String>opsForStream().read(
                        Consumer.from(RedisCouponIssueSyncGateway.DLQ_GROUP_NAME, "crashed-recovery-worker"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(dlqStreamKey(), ReadOffset.lastConsumed()))
                .getFirst()
                .getId();
        // dlqMaxDeliveryCount(3)를 이미 넘겨 4번 배달된 것처럼 강제로 올린다.
        redisTemplate.<String, String>opsForStream().claim(
                dlqStreamKey(),
                RedisCouponIssueSyncGateway.DLQ_GROUP_NAME,
                "crashed-recovery-worker",
                XClaimOptions.minIdle(Duration.ZERO).ids(recordId).retryCount(4));
        awaitDlqPendingMinIdle();
        CouponIssueDlqRecoveryConsumer consumer = newConsumer(properties);

        // when
        consumer.recover();

        // then
        verify(repository, never()).saveBatch(anyLong(), anyList());
        verify(repository).recordFailure(eq(COUPON_ID), eq(100L), eq(ErrorCode.INTERNAL_ERROR), any());
        assertThat(currentStock()).isEqualTo("6");
        assertThat(redisTemplate.opsForSet().isMember(issuedMembersKey(), "100")).isFalse();
        assertThat(dlqPendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(dlqStreamKey())).isZero();
    }

    private CouponIssueDlqRecoveryConsumer newConsumer(CouponIssueSyncProperties properties) {
        return new CouponIssueDlqRecoveryConsumer(
                gateway, issueGateway, repository, properties, streamGroupRecovery, activeCouponIdHolder);
    }

    private void addDlqEvent(String eventId, long memberId, long reservedAtEpochSecond) {
        redisTemplate.opsForStream().add(
                dlqStreamKey(),
                Map.of(
                        "eventId", eventId,
                        "eventType", "COUPON_ISSUE_RESERVED",
                        "schemaVersion", "1",
                        "couponId", Long.toString(COUPON_ID),
                        "memberId", Long.toString(memberId),
                        "reservedAtEpochSecond", Long.toString(reservedAtEpochSecond)));
    }

    private void awaitDlqPendingMinIdle() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long dlqPendingCount() {
        return redisTemplate.opsForStream()
                .pending(dlqStreamKey(), RedisCouponIssueSyncGateway.DLQ_GROUP_NAME)
                .getTotalPendingMessages();
    }
}
