package com.mocou.issue.sync;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import com.mocou.coupon.CouponStatusChangedEvent;
import com.mocou.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DB 저장({@link CouponIssueSyncRepository})은 mock으로 대체해서, Redis
 * 버퍼링/카운트·시간 기반 flush 트리거만 빠르게 검증한다. 실제 DB 반영/중복
 * skip과 outbox 알림 큐잉은 {@link JdbcCouponIssueSyncRepositoryIntegrationTest}가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueSyncConsumerIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Mock
    private CouponIssueSyncRepository repository;

    @Test
    @DisplayName("청크 크기만큼 쌓이면 시간 창을 기다리지 않고 즉시 flush한다")
    void flushesImmediatelyWhenChunkSizeReached() {
        // given
        CouponIssueSyncProperties properties = properties(3, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

        addEvent("event-1", 100L, 1L);
        addEvent("event-2", 101L, 2L);
        addEvent("event-3", 102L, 3L);

        // when
        consumer.consume();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue())
                .extracting(CouponIssueSyncEvent::memberId)
                .containsExactly(100L, 101L, 102L);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("청크 크기가 안 차도 배치 시간 창이 지나면 flush한다")
    void flushesWhenBatchWindowElapsesBeforeChunkSizeReached() throws InterruptedException {
        // given
        CouponIssueSyncProperties properties = properties(100, 300);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

        addEvent("event-1", 100L, 1L);
        addEvent("event-2", 101L, 2L);

        // when
        // 청크 크기(100건)에 한참 못 미치는 2건만 있는 시점 — 아직 flush되면 안 된다.
        consumer.consume();

        // then
        verify(repository, never()).saveBatch(anyLong(), anyList());

        // when
        Thread.sleep(350);
        consumer.consume();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("다른 컨슈머가 읽고 ACK 못 한 채 오래 방치된 엔트리를 인수해 처리한다")
    void reclaimsStalePendingEntryFromCrashedConsumer() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        properties.setPendingMinIdleMs(100);
        properties.setPendingCheckIntervalMs(0);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

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

        // when
        consumer.consume();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().memberId()).isEqualTo(100L);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("재시도 한도를 초과한 엔트리는 재처리 대신 보상하고 실패 로그를 남긴 뒤 XACK한다")
    void compensatesAndLogsFailureWhenRetryLimitExceeded() {
        // given
        CouponIssueSyncProperties properties = properties(100, 5_000);
        properties.setPendingMinIdleMs(100);
        properties.setPendingCheckIntervalMs(0);
        properties.setMaxDeliveryCount(3);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

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

        // when
        consumer.consume();

        // then
        verify(repository, never()).saveBatch(anyLong(), anyList());
        // outbox: 실패 로그와 알림 큐잉은 repository.recordFailure 내부(같은 트랜잭션)에서
        // 처리된다 — JdbcCouponIssueSyncRepositoryIntegrationTest가 그 부분을 검증한다.
        verify(repository).recordFailure(eq(COUPON_ID), eq(100L), eq(ErrorCode.INTERNAL_ERROR), any());
        assertThat(currentStock()).isEqualTo("6");
        assertThat(redisTemplate.opsForSet().isMember(issuedMembersKey(), "100")).isFalse();
        assertThat(pendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(issueStreamKey())).isZero();
    }

    @Test
    @DisplayName("정상 flush 후에는 XACK된 엔트리를 스트림에서도 삭제한다")
    void deletesStreamEntriesAfterFlush() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

        addEvent("event-1", 100L, 1L);

        // when
        consumer.consume();

        // then
        assertThat(pendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(issueStreamKey())).isZero();
    }

    // Redis 초기화(FLUSHALL/재시작 등)로 컨슈머 그룹이 사라져도, groupEnsuredForCouponId
    // 캐시는 여전히 "이미 만들었다"고 믿는다. 그 상태에서 실제 Redis 명령이 NOGROUP으로
    // 실패하면 캐시를 무효화해서 다음 tick에 스스로 그룹을 재생성하는지 검증한다.
    @Test
    @DisplayName("컨슈머 그룹이 외부에서 사라지면 다음 tick에 스스로 재생성해 복구한다")
    void recoversAfterConsumerGroupIsDeletedExternally() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        CouponIssueSyncConsumer consumer = new CouponIssueSyncConsumer(
                redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();
        given(repository.saveBatch(anyLong(), anyList()))
                .willAnswer(invocation -> invocation.getArgument(1));

        // 정상적으로 한 번 처리해서 컨슈머 내부 캐시(그룹을 이미 만들었다는 것)를 채운다.
        addEvent("event-1", 100L, 1L);
        consumer.consume();
        verify(repository).saveBatch(eq(COUPON_ID), anyList());

        // Redis 초기화로 그룹만 사라진 상황을 재현한다 (캐시는 여전히 "있다"고 믿는 상태).
        redisTemplate.<String, String>opsForStream()
                .destroyGroup(issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);
        addEvent("event-2", 101L, 2L);

        // when, then
        // 캐시를 그대로 믿고 그룹을 재생성하지 않아 첫 시도는 NOGROUP으로 실패한다.
        assertThatThrownBy(consumer::consume).isInstanceOf(RedisSystemException.class);

        // 캐시가 무효화됐으니 다음 tick엔 그룹을 다시 만들고 정상 처리된다.
        consumer.consume();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CouponIssueSyncEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveBatch(eq(COUPON_ID), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(list -> list.getFirst().memberId())
                .containsExactly(100L, 101L);
        // initOpenCouponIds() 1회 + 최초 그룹 생성 1회 + 복구 시 그룹 재생성 1회 = 3회.
        // openCouponIds 캐시가 이벤트 유실 등으로 stale해졌을 가능성까지 이 순간에 같이 회복한다.
        verify(repository, times(3)).findOpenCouponIds();
    }

    // 그룹 생성 시점(최초 1회)에 한 번 더 조회하는 건 의도한 동작이다 - 그룹이 실제로
    // 캐시 밖에서 다시 만들어지지 않는 이상, 매 tick DB를 두드리진 않는다는 게 이 테스트의 핵심.
    @Test
    @DisplayName("consume()를 여러 번 호출해도 그룹 생성 이후엔 open 쿠폰 목록을 추가로 조회하지 않는다")
    void queriesOpenCouponIdsOnlyOnceAcrossMultipleConsumeCalls() {
        // given
        CouponIssueSyncProperties properties = properties(100, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.initOpenCouponIds();

        // when
        // 10ms 간격으로 반복 호출되는 실제 상황을 재현 — 그룹이 이미 있으면 매번 DB를 조회하면 안 된다.
        consumer.consume();
        consumer.consume();
        consumer.consume();

        // then
        // initOpenCouponIds()에서 1번 + 최초 그룹 생성 시점에 1번, 이후로는 추가 조회 없음.
        verify(repository, times(2)).findOpenCouponIds();
    }

    @Test
    @DisplayName("CouponStatusChangedEvent를 받으면 open 쿠폰 목록을 다시 조회해 반영한다")
    void refreshesOpenCouponIdsOnStatusChangedEvent() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        CouponIssueSyncConsumer consumer =
                new CouponIssueSyncConsumer(redisTemplate, gateway, issueGateway, repository, properties);
        given(repository.findOpenCouponIds()).willReturn(List.of());
        consumer.initOpenCouponIds();
        given(repository.saveBatch(anyLong(), anyList()))
                .willAnswer(invocation -> invocation.getArgument(1));

        addEvent("event-1", 100L, 1L);
        consumer.consume();
        verify(repository, never()).saveBatch(anyLong(), anyList());

        // when
        // 관리자 API가 쿠폰을 OPEN으로 바꾸고 이벤트를 발행했다고 가정한다.
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        consumer.onCouponStatusChanged(new CouponStatusChangedEvent(COUPON_ID));
        consumer.consume();

        // then
        verify(repository).saveBatch(eq(COUPON_ID), anyList());
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
