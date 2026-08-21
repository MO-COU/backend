package com.mocou.issue.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;

/**
 * DB 저장({@link CouponIssueSyncRepository})은 mock으로 대체해서, Redis
 * 버퍼링/카운트·시간 기반 flush 트리거만 빠르게 검증한다. 실제 DB 반영/중복
 * skip은 {@link JdbcCouponIssueSyncRepositoryIntegrationTest}가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueSyncConsumerIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Mock
    private CouponIssueSyncRepository repository;

    @Test
    @DisplayName("청크 크기만큼 쌓이면 시간 창을 기다리지 않고 즉시 flush한다")
    void flushesImmediatelyWhenChunkSizeReached() {
        CouponIssueSyncProperties properties = properties(3, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));

        addEvent("event-1", 100L, 1L);
        addEvent("event-2", 101L, 2L);
        addEvent("event-3", 102L, 3L);

        consumer.consume();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(org.mockito.ArgumentMatchers.eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue())
                .extracting(CouponIssueSyncEvent::memberId)
                .containsExactly(100L, 101L, 102L);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("청크 크기가 안 차도 배치 시간 창이 지나면 flush한다")
    void flushesWhenBatchWindowElapsesBeforeChunkSizeReached() throws InterruptedException {
        CouponIssueSyncProperties properties = properties(100, 300);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));

        addEvent("event-1", 100L, 1L);
        addEvent("event-2", 101L, 2L);

        // 청크 크기(100건)에 한참 못 미치는 2건만 있는 시점 — 아직 flush되면 안 된다.
        consumer.consume();
        verify(repository, never()).saveBatch(anyLong(), org.mockito.ArgumentMatchers.anyList());

        Thread.sleep(350);
        consumer.consume();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1))
                .saveBatch(org.mockito.ArgumentMatchers.eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(pendingCount()).isZero();
    }

    private CouponIssueSyncProperties properties(int chunkSize, long batchWindowMs) {
        CouponIssueSyncProperties properties = new CouponIssueSyncProperties();
        properties.setChunkSize(chunkSize);
        properties.setBatchWindowMs(batchWindowMs);
        return properties;
    }

    private void addEvent(String eventId, long memberId, long reservedAtEpochSecond) {
        redisTemplate.opsForStream().add(
                issueStreamKey(),
                Map.of(
                        "eventId", eventId,
                        "eventType", "COUPON_ISSUE_RESERVED",
                        "schemaVersion", "1",
                        "couponId", Long.toString(COUPON_ID),
                        "memberId", Long.toString(memberId),
                        "reservedAtEpochSecond", Long.toString(reservedAtEpochSecond)));
    }

    private long pendingCount() {
        PendingMessagesSummary summary =
                redisTemplate.opsForStream().pending(issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);
        return summary.getTotalPendingMessages();
    }
}
