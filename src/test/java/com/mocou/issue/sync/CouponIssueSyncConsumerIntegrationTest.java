package com.mocou.issue.sync;

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
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import com.mocou.global.exception.ErrorCode;

/**
 * DB 저장({@link CouponIssueSyncRepository})은 mock으로 대체해서, Redis
 * 버퍼링/카운트·시간 기반 flush 트리거만 빠르게 검증한다. 실제 DB 반영/중복
 * skip과 outbox 알림 큐잉은 {@link JdbcCouponIssueSyncRepositoryIntegrationTest}가 담당한다.
 * DLQ로 옮긴 뒤의 복구/최종 보상은 {@link CouponIssueDlqRecoveryConsumerIntegrationTest}가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueSyncConsumerIntegrationTest
        extends RedisCouponIssueSyncIntegrationTestSupport {

    @Mock
    private CouponIssueSyncRepository repository;

    private RedisStreamGroupRecovery streamGroupRecovery;
    private ActiveCouponIdHolder activeCouponIdHolder;

    @BeforeEach
    void setUpSharedComponents() {
        streamGroupRecovery = new RedisStreamGroupRecovery(redisTemplate);
        activeCouponIdHolder = new ActiveCouponIdHolder(repository);
    }

    @Test
    @DisplayName("청크 크기만큼 쌓이면 시간 창을 기다리지 않고 즉시 flush한다")
    void flushesImmediatelyWhenChunkSizeReached() {
        // given
        CouponIssueSyncProperties properties = properties(3, 5_000);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

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
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

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
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

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
    @DisplayName("재시도 한도를 초과한 엔트리는 보상하지 않고 DLQ로 옮긴 뒤 원본 스트림에서 XACK한다")
    void movesExhaustedEntryToDlqWithoutCompensatingWhenRetryLimitExceeded() {
        // given
        CouponIssueSyncProperties properties = properties(100, 5_000);
        properties.setPendingMinIdleMs(100);
        properties.setPendingCheckIntervalMs(0);
        properties.setMaxDeliveryCount(3);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

        gateway.ensureConsumerGroup(COUPON_ID);
        // reserveAndAppendEvent가 실제로 남기는 상태(재고 차감 + 발급 회원 등록)를
        // 직접 재현해서, 이번엔 이 상태가 "그대로 유지"되는지(보상하지 않는지) 검증한다.
        setStock(5);
        redisTemplate.opsForZSet().add(issuedMembersKey(), "100", 1);
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
        // 메인 스트림은 여기서 최종 실패를 기록하지 않는다 — DLQ 복구가 최종 한도를
        // 넘겼을 때만 기록한다(CouponIssueDlqRecoveryConsumerIntegrationTest 담당).
        verify(repository, never()).recordFailure(anyLong(), anyLong(), any(), any());
        // 대신 "재시도 한도를 넘겨 DLQ로 넘어갔다"는 사실만 별도 사유로 기록한다 —
        // 아직 최종 실패가 아니라 알림은 이 시점에 보내지 않는다(recordFailure가 담당).
        verify(repository).recordRetryEscalation(
                eq(COUPON_ID), eq(100L), eq(ErrorCode.SYNC_RETRY_LIMIT_EXCEEDED), any());
        // 회원의 Redis 예약을 그대로 유지한다 — DLQ 복구가 나중에 실제로 발급을
        // 완성시킬 수 있어야 하므로 여기서 보상(재고 원복)하지 않는다.
        assertThat(currentStock()).isEqualTo("5");
        assertThat(redisTemplate.opsForZSet().score(issuedMembersKey(), "100")).isEqualTo(1.0);
        // 원본 스트림에서는 XACK+XDEL로 완전히 제거된다.
        assertThat(pendingCount()).isZero();
        assertThat(redisTemplate.opsForStream().size(issueStreamKey())).isZero();
        // DLQ로 그대로 옮겨졌다 — 같은 필드를 그대로 들고 있어야 한다.
        List<MapRecord<String, String, String>> dlqRecords = redisTemplate.<String, String>opsForStream()
                .read(StreamOffset.fromStart(dlqStreamKey()));
        assertThat(dlqRecords).hasSize(1);
        assertThat(dlqRecords.getFirst().getValue()).containsEntry("memberId", "100");
    }

    @Test
    @DisplayName("정상 flush 후에는 XACK된 엔트리를 스트림에서도 삭제한다")
    void deletesStreamEntriesAfterFlush() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

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
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);
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
        // ActiveCouponIdHolder.init()에서 딱 1회 조회한 뒤로는 DB를 다시 조회하지 않는다 —
        // 그룹 재생성은 활성 쿠폰(이벤트로만 바뀌는 값)을 그대로 신뢰하고 진행한다.
        verify(repository, times(1)).findOpenCouponIds();
    }

    // 그룹을 다시 만들어야 하는 상황(캐시 무효화 등)에서도 DB를 추가로 조회하지 않는 것이
    // 이 테스트의 핵심 — 활성 쿠폰은 이벤트로만 바뀌는 값이라 그룹 생성 시점에
    // 다시 확인할 필요가 없다.
    @Test
    @DisplayName("consume()를 여러 번 호출해도 최초 조회 이후엔 open 쿠폰 목록을 추가로 조회하지 않는다")
    void queriesOpenCouponIdsOnlyOnceAcrossMultipleConsumeCalls() {
        // given
        CouponIssueSyncProperties properties = properties(100, 5_000);
        given(repository.findOpenCouponIds()).willReturn(List.of(COUPON_ID));
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);

        // when
        // 10ms 간격으로 반복 호출되는 실제 상황을 재현 — 그룹이 이미 있으면 매번 DB를 조회하면 안 된다.
        consumer.consume();
        consumer.consume();
        consumer.consume();

        // then
        // ActiveCouponIdHolder.init()에서 1번뿐, 이후로는 추가 조회 없음.
        verify(repository, times(1)).findOpenCouponIds();
    }

    @Test
    @DisplayName("CouponSyncTargetChangedEvent를 받으면 DB 조회 없이 즉시 활성 쿠폰을 전환한다")
    void switchesActiveCouponImmediatelyOnSyncTargetChangedEvent() {
        // given
        CouponIssueSyncProperties properties = properties(1, 5_000);
        given(repository.findOpenCouponIds()).willReturn(List.of());
        activeCouponIdHolder.init();
        CouponIssueSyncConsumer consumer = newConsumer(properties);
        given(repository.saveBatch(anyLong(), anyList()))
                .willAnswer(invocation -> invocation.getArgument(1));

        addEvent("event-1", 100L, 1L);
        consumer.consume();
        verify(repository, never()).saveBatch(anyLong(), anyList());

        // when
        // 부하 테스트 시작 등 발급 처리를 실제로 시작시키는 쪽이 이 이벤트를 발행했다고 가정한다.
        activeCouponIdHolder.onSyncTargetChanged(new CouponSyncTargetChangedEvent(COUPON_ID));
        consumer.consume();

        // then
        verify(repository).saveBatch(eq(COUPON_ID), anyList());
        // 이벤트는 couponId를 그대로 신뢰하므로 전환을 위해 DB를 다시 조회하지 않는다 —
        // ActiveCouponIdHolder.init()에서의 1번뿐.
        verify(repository, times(1)).findOpenCouponIds();
    }

    private CouponIssueSyncConsumer newConsumer(CouponIssueSyncProperties properties) {
        return new CouponIssueSyncConsumer(
                gateway, repository, properties, streamGroupRecovery, activeCouponIdHolder);
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

    // issueSequence/remainingAtIssue 값 자체는 이 클래스의 어떤 테스트도 검증하지 않는다
    // (배치 버퍼링/flush 트리거만 다룬다) - reservedAtEpochSecond를 그대로 재사용해도
    // 충분하다. 실제 값 저장 검증은 JdbcCouponIssueSyncRepositoryIntegrationTest가 담당한다.
    private void addEvent(String eventId, long memberId, long reservedAtEpochSecond) {
        redisTemplate.opsForStream().add(
                issueStreamKey(),
                Map.of(
                        "eventId", eventId,
                        "eventType", "COUPON_ISSUE_RESERVED",
                        "schemaVersion", "2",
                        "couponId", Long.toString(COUPON_ID),
                        "memberId", Long.toString(memberId),
                        "issueSequence", Long.toString(reservedAtEpochSecond),
                        "remainingAtIssue", Long.toString(reservedAtEpochSecond),
                        "reservedAtEpochSecond", Long.toString(reservedAtEpochSecond)));
    }

    private long pendingCount() {
        PendingMessagesSummary summary =
                redisTemplate.opsForStream().pending(issueStreamKey(), RedisCouponIssueSyncGateway.GROUP_NAME);
        return summary.getTotalPendingMessages();
    }
}
