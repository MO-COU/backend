package com.mocou.issue.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

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
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
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
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
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

    @Test
    @DisplayName("다른 컨슈머가 읽고 ACK 못 한 채 오래 방치된 엔트리를 인수해 처리한다")
    void reclaimsStalePendingEntryFromCrashedConsumer() {
        CouponIssueSyncProperties properties = properties(1, 5_000);
        properties.setPendingMinIdleMs(100);
        properties.setPendingCheckIntervalMs(0);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));

        gateway.ensureConsumerGroup(COUPON_ID);
        addEvent("event-1", 100L, 1L);
        // "crashed-worker"가 읽어가기만 하고 ACK를 안 한 상황을 재현 — 이 엔트리는
        // 이제 PEL에 "crashed-worker" 소유로 남아 우리 컨슈머(sync-worker-1)의
        // 일반 read()(">")로는 절대 다시 안 잡힌다.
        redisTemplate.<String, String>opsForStream().read(
                Consumer.from(RedisCouponIssueSyncGateway.GROUP_NAME, "crashed-worker"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(issueStreamKey(), ReadOffset.lastConsumed()));

        awaitPendingMinIdle();

        consumer.consume();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(org.mockito.ArgumentMatchers.eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().memberId()).isEqualTo(100L);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("재시도 한도를 초과한 엔트리는 재처리 대신 보상하고 실패 로그를 남긴 뒤 XACK한다")
    void compensatesAndLogsFailureWhenRetryLimitExceeded() {
        CouponIssueSyncProperties properties = properties(100, 5_000);
        properties.setPendingMinIdleMs(100);
        properties.setPendingCheckIntervalMs(0);
        properties.setMaxDeliveryCount(3);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));

        gateway.ensureConsumerGroup(COUPON_ID);
        // reserveAndAppendEvent가 실제로 남기는 상태(재고 차감 + 발급 회원 등록)를
        // 직접 재현해서, compensate가 이걸 원복하는지 끝까지 검증할 수 있게 한다.
        setStock(5);
        redisTemplate.opsForSet().add(issuedMembersKey(), "100");
        addEvent("event-1", 100L, 1L);
        RecordId recordId = redisTemplate.<String, String>opsForStream().read(
                        Consumer.from(RedisCouponIssueSyncGateway.GROUP_NAME, "crashed-worker"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(issueStreamKey(), ReadOffset.lastConsumed()))
                .getFirst()
                .getId();
        // maxDeliveryCount(3)를 이미 넘겨 4번 배달된 것처럼 재시도 카운터를 강제로
        // 올려, 실제로 3번을 재시도하지 않고도 한도 초과 상태를 재현한다.
        redisTemplate.<String, String>opsForStream().claim(
                issueStreamKey(),
                RedisCouponIssueSyncGateway.GROUP_NAME,
                "crashed-worker",
                XClaimOptions.minIdle(Duration.ZERO).ids(recordId).retryCount(4));

        awaitPendingMinIdle();

        consumer.consume();

        verify(repository, never()).saveBatch(anyLong(), org.mockito.ArgumentMatchers.anyList());
        verify(repository).recordFailure(eq(COUPON_ID), eq(100L), eq("INTERNAL_ERROR"), any());
        assertThat(currentStock()).isEqualTo("6");
        assertThat(redisTemplate.opsForSet().isMember(issuedMembersKey(), "100")).isFalse();
        assertThat(pendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(issueStreamKey())).isZero();
    }

    @Test
    @DisplayName("정상 flush 후에는 XACK된 엔트리를 스트림에서도 삭제한다")
    void deletesStreamEntriesAfterFlush() {
        CouponIssueSyncProperties properties = properties(1, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));

        addEvent("event-1", 100L, 1L);

        consumer.consume();

        assertThat(pendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(issueStreamKey())).isZero();
    }

    private void awaitPendingMinIdle() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
