package com.mocou.issue.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.RedisCouponIssueGateway;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 발급 이벤트 Stream을 읽어 DB로 동기화하는 컨슈머.
 *
 * <p>Spring Batch 없이 순수 {@code @Scheduled} + 로컬 버퍼로 "100건 또는
 * {@code batchWindowMs}" 배치를 직접 구현한다 — 중복 실행 방지는 fixedDelay가,
 * 재시작 복구는 Redis Consumer Group의 PEL이 대신해줘서 Spring Batch가 필요 없다.
 * {@code BLOCK}은 count/시간이 찰 때까지 서버가 모아주는 게 아니라 "새 이벤트가
 * 생기면 그 순간 쌓인 만큼 즉시 반환"이라, 누적은 컨슈머가 직접 해야 한다.
 *
 * <p>{@code mocou.issue.sync.enabled=true}일 때만 동작한다(기본 꺼짐) —
 * 안 그러면 이 빈이 뜬 모든 테스트가 OPEN 쿠폰이 있을 때마다 Redis 연결을
 * 시도해 무관한 테스트까지 오염시킨다.
 *
 * <p>여러 쿠폰이 동시에 {@code OPEN} 상태일 수 있지만, 실제로 발급을 처리하는 것은
 * 항상 하나뿐이다(관리자가 하나씩 순차로 진행시키는 운영 절차). {@link #consume()}은
 * {@code activeCouponId} 하나만 폴링하며, 활성 쿠폰은 {@link #onSyncTargetChanged}로
 * 전환된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mocou.issue.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponIssueSyncConsumer {

    private static final String GROUP_NAME = RedisCouponIssueSyncGateway.GROUP_NAME;
    // 인스턴스가 하나뿐인 MVP라 고정값 — 여러 인스턴스를 띄우면 인스턴스마다 달라야 함.
    private static final String CONSUMER_NAME = "sync-worker-1";
    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");
    private static final String NO_GROUP_ERROR = "NOGROUP";

    private final StringRedisTemplate redisTemplate;
    private final RedisCouponIssueSyncGateway syncGateway;
    private final RedisCouponIssueGateway issueGateway;
    private final CouponIssueSyncRepository repository;
    private final CouponIssueSyncProperties properties;

    private final StreamBuffer buffer = new StreamBuffer();
    // ensureConsumerGroup을 이미 보장해준 couponId. 매 틱 부르면 낭비라 "바뀔 때만" 확인.
    private Long groupEnsuredForCouponId;
    // XPENDING을 다시 확인해도 되는 시점. 매 틱마다 하면 낭비라 pendingCheckIntervalMs 간격으로 제한.
    private Instant nextPendingCheckAt = Instant.EPOCH;
    // 지금 실제로 폴링 중인 couponId. consume() 스레드(@Scheduled)에서만 읽고 쓰므로
    // activeCouponId와 달리 volatile이 필요 없다 — 매 tick 이 값과 activeCouponId를
    // 비교해서 대상이 바뀌었는지만 판단한다.
    private Long polledCouponId;
    // "지금 활성화해야 할" couponId. 앱 기동 시 DB 조회로 한 번 정해지고, 이후로는
    // CouponSyncTargetChangedEvent(발급을 시작시키는 API가 발행)를 받을 때만 바뀐다 —
    // 매 틱(10ms) DB를 조회하면 그 자체가 병목이라서다. @Scheduled 스레드와 이벤트를
    // 발행하는 쪽(API 요청 스레드)이 다를 수 있어 volatile로 가시성을 보장한다.
    private volatile Long activeCouponId;

    /** 빈이 뜰 때 한 번만 DB를 조회해 최초 활성 쿠폰을 정한다 — 그 이후는 이벤트로만 바뀐다. */
    @PostConstruct
    void initActiveCouponId() {
        List<Long> openCouponIds = repository.findOpenCouponIds();
        activeCouponId = openCouponIds.isEmpty() ? null : openCouponIds.getFirst();
    }

    /**
     * 특정 쿠폰의 발급 처리를 실제로 시작시키는 쪽(예: 부하 테스트 시작 API)이 발행하는
     * 이벤트를 받아 활성 쿠폰을 즉시 전환한다. DB를 다시 조회하지 않고 이벤트가 담아온
     * couponId를 그대로 신뢰한다 — 여러 쿠폰이 동시에 OPEN이어도 실제로 처리할 하나는
     * 이 이벤트가 정하기 때문이다.
     */
    @EventListener
    void onSyncTargetChanged(CouponSyncTargetChangedEvent event) {
        activeCouponId = event.couponId();
    }

    // fixedDelay라 consume()은 절대 자기 자신과 동시에 두 번 안 돈다 — 상태 필드 동시성 걱정 없음.
    @Scheduled(fixedDelayString = "${mocou.issue.sync.poll-interval-ms:10}")
    public void consume() {
        Long couponId = activeCouponId;
        if (couponId == null) {
            return;
        }
        if (!couponId.equals(polledCouponId)) {
            switchActiveCoupon(couponId);
        }

        pollOnce(couponId);
    }

    /**
     * 활성 쿠폰이 바뀌었을 때 이전 쿠폰에 묶여 있던 폴링 상태를 전부 리셋한다.
     * consume() 스레드에서만 호출되므로 buffer/groupEnsuredForCouponId 등을 잠금 없이
     * 건드려도 안전하다.
     *
     * <p>운영 시나리오상 다음 쿠폰은 이전 쿠폰의 처리가 완전히 끝난 뒤에만 활성화되므로
     * 전환 시점에 버퍼가 남아 있으면 안 된다. 그래도 버퍼가 남아 있다면(운영 절차를 어기고
     * 전환한 경우) 그 이벤트들은 아직 XACK되지 않았으므로 유실은 아니다 — Redis PEL에
     * 그대로 남아, 이전 couponId가 다시 활성화되면 재처리된다.
     */
    private void switchActiveCoupon(long couponId) {
        if (polledCouponId != null && !buffer.records.isEmpty()) {
            log.warn(
                    "이전 쿠폰({})의 미확정 배치가 {}건 남은 채로 활성 쿠폰이 {}로 바뀌었다. "
                            + "버퍼만 비우고 XACK는 하지 않으므로 Redis PEL에는 그대로 남는다.",
                    polledCouponId, buffer.records.size(), couponId);
        }
        buffer.records.clear();
        buffer.deadline = null;
        groupEnsuredForCouponId = null;
        nextPendingCheckAt = Instant.EPOCH;
        polledCouponId = couponId;
    }

    /**
     * 배치를 완성하는 게 아니라 "한 스텝 진행"시킨다 — 조건이 찰 때까지 여러 번 불린다.
     *
     * <p>Redis가 초기화(FLUSHALL/재시작 등)되면 스트림과 컨슈머 그룹이 통째로 사라지는데,
     * {@link #groupEnsuredForCouponId}는 JVM 메모리 캐시라 이 사실을 모른다. 그래서 실제
     * Redis 명령(XPENDING/XREADGROUP 등)이 {@code NOGROUP}으로 실패하는 걸 여기서 감지해
     * 캐시를 무효화한다 — 다음 tick에 {@link #ensureConsumerGroup}이 그룹을 다시 만든다.
     * 앱 재시작 없이도 스스로 복구된다.
     */
    private void pollOnce(long couponId) {
        String streamKey = CouponRedisKey.issueStream(couponId);
        ensureConsumerGroup(couponId);

        try {
            reclaimStalePendingEntries(streamKey);

            int remaining = properties.getChunkSize() - buffer.records.size();
            if (remaining > 0) {
                List<MapRecord<String, String, String>> messages = readNext(streamKey, remaining);
                if (messages != null && !messages.isEmpty()) {
                    // 첫 이벤트가 들어온 순간에만 데드라인을 고정한다.
                    if (buffer.records.isEmpty()) {
                        buffer.deadline = Instant.now().plusMillis(properties.getBatchWindowMs());
                    }
                    buffer.records.addAll(messages);
                }
            }

            flushIfDue(couponId, streamKey);
        } catch (RedisSystemException exception) {
            if (isNoGroupError(exception)) {
                log.warn(
                        "Redis 초기화 등으로 컨슈머 그룹이 사라진 것을 감지했다. "
                                + "캐시를 무효화해 다음 tick에 재생성한다. couponId={}",
                        couponId);
                groupEnsuredForCouponId = null;
            }
            throw exception;
        }
    }

    /**
     * XREADGROUP은 그룹이 없으면 NOGROUP 에러를 던지므로 미리 보장해둔다.
     *
     * <p>{@code activeCouponId}는 더 이상 "DB 재조회로 최신화하는 캐시"가 아니라
     * {@code CouponSyncTargetChangedEvent}가 직접 전달한, 신뢰할 수 있는 전환 대상이라
     * 그룹 생성 직전에 DB를 다시 확인할 필요가 없다.
     */
    private void ensureConsumerGroup(long couponId) {
        if (groupEnsuredForCouponId != null && groupEnsuredForCouponId == couponId) {
            return;
        }

        syncGateway.ensureConsumerGroup(couponId);
        groupEnsuredForCouponId = couponId;
    }

    private boolean isNoGroupError(RedisSystemException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(NO_GROUP_ERROR);
    }

    /**
     * 오래 미확인 상태인 PEL 엔트리를 처리한다. 컨슈머가 크래시로 재시작됐거나,
     * XREADGROUP 응답이 유실됐거나(Redis는 이미 PEL에 반영했지만 우리가 못 받은
     * 경우) 하는 상황을 커버한다.
     *
     * <p>{@code totalDeliveryCount}(Redis가 직접 세는 누적 배달 횟수) 기준으로
     * 둘로 나눈다: {@code maxDeliveryCount} 이하면 아직 가망이 있다고 보고 버퍼에
     * 합류시켜 3~9번의 일반 배치 로직(파싱→저장→XACK)을 그대로 재사용하고, 이미
     * 처리된 건은 saveOne의 DuplicateKeyException skip이 걸러준다. 그 이상이면
     * 포기하고 {@link #reclaimForCompensation}으로 보낸다.
     */
    private void reclaimStalePendingEntries(String streamKey) {
        if (Instant.now().isBefore(nextPendingCheckAt)) {
            return;
        }
        nextPendingCheckAt = Instant.now().plusMillis(properties.getPendingCheckIntervalMs());

        int room = properties.getChunkSize() - buffer.records.size();
        if (room <= 0) {
            return;
        }

        PendingMessages pending = redisTemplate.<String, String>opsForStream()
                .pending(streamKey, GROUP_NAME, Range.unbounded(), room);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        // pendingMinIdleMs보다 짧게 대기 중인 건 우리 자신의 버퍼가 아직 못
        // 비운 정상적인 진행 중 항목일 수 있으므로 건드리지 않는다.
        List<PendingMessage> stale = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().toMillis()
                        >= properties.getPendingMinIdleMs())
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        List<String> retryableIds = stale.stream()
                .filter(message -> message.getTotalDeliveryCount() <= properties.getMaxDeliveryCount())
                .map(PendingMessage::getIdAsString)
                .toList();
        List<String> exhaustedIds = stale.stream()
                .filter(message -> message.getTotalDeliveryCount() > properties.getMaxDeliveryCount())
                .map(PendingMessage::getIdAsString)
                .toList();

        reclaimForRetry(streamKey, retryableIds);
        reclaimForCompensation(streamKey, exhaustedIds);
    }

    /** 아직 재시도 한도 안쪽인 엔트리를 인수해 버퍼에 합류시킨다. */
    private void reclaimForRetry(String streamKey, List<String> ids) {
        List<MapRecord<String, String, String>> claimed = claim(streamKey, ids);
        if (claimed.isEmpty()) {
            return;
        }

        if (buffer.records.isEmpty()) {
            buffer.deadline = Instant.now().plusMillis(properties.getBatchWindowMs());
        }
        buffer.records.addAll(claimed);
    }

    /**
     * maxDeliveryCount를 넘겨 더 이상 재시도하지 않기로 포기한 엔트리를 처리한다
     * (Issue #39 체크리스트 10번). 일반 배치(saveBatch)로는 절대 보내지 않고, 대신
     * Redis 재고를 원복(compensate)한 뒤 issue_failure_log에 남기고 XACK+XDEL로
     * 스트림에서 제거한다. compensate는 멱등(compensate-coupon.lua의 ZSCORE 가드)이라
     * 이 메서드 도중 예외가 나 다음 tick에 그대로 재시도돼도 재고를 이중으로
     * 원복하지 않는다.
     */
    private void reclaimForCompensation(String streamKey, List<String> ids) {
        List<MapRecord<String, String, String>> claimed = claim(streamKey, ids);
        if (claimed.isEmpty()) {
            return;
        }

        for (MapRecord<String, String, String> record : claimed) {
            CouponIssueSyncEvent event = parse(record);
            issueGateway.compensate(event.couponId(), event.memberId());
            // outbox: 실패 로그와 알림 큐잉이 recordFailure 안에서 같은 트랜잭션으로 처리된다.
            repository.recordFailure(
                    event.couponId(),
                    event.memberId(),
                    ErrorCode.INTERNAL_ERROR,
                    LocalDateTime.now(COUPON_TIME_ZONE));
        }

        String[] recordIds = claimed.stream()
                .map(record -> record.getId().getValue())
                .toArray(String[]::new);
        acknowledgeAndDelete(streamKey, recordIds);
    }

    private List<MapRecord<String, String, String>> claim(String streamKey, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream()
                .claim(streamKey, GROUP_NAME, CONSUMER_NAME,
                        XClaimOptions.minIdle(Duration.ofMillis(properties.getPendingMinIdleMs()))
                                .ids(ids));
        return claimed == null ? List.of() : claimed;
    }

    /** block 시간을 "데드라인까지 남은 시간"으로 매번 재계산해야 데드라인에 정확히 깨어난다. */
    private List<MapRecord<String, String, String>> readNext(String streamKey, int remaining) {
        long blockMs = buffer.deadline == null
                ? properties.getBatchWindowMs()
                : Math.max(0, Duration.between(Instant.now(), buffer.deadline).toMillis());

        StreamReadOptions options = StreamReadOptions.empty().count(remaining);
        // BLOCK 0은 Redis 프로토콜상 "무한 대기"라 논블로킹과 다르다 — blockMs<=0이면 옵션 자체를 뺀다.
        if (blockMs > 0) {
            options = options.block(Duration.ofMillis(blockMs));
        }

        return redisTemplate.<String, String>opsForStream().read(
                Consumer.from(GROUP_NAME, CONSUMER_NAME),
                options,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    }

    /** "100건 또는 5초" 조건을 판정하고, 만족하면 DB 반영 + XACK까지 처리한다. */
    private void flushIfDue(long couponId, String streamKey) {
        if (buffer.records.isEmpty()) {
            return;
        }

        boolean countReached = buffer.records.size() >= properties.getChunkSize();
        boolean timeElapsed = buffer.deadline != null && !Instant.now().isBefore(buffer.deadline);
        if (!countReached && !timeElapsed) {
            return;
        }

        List<CouponIssueSyncEvent> events = buffer.records.stream().map(this::parse).toList();

        // saveBatch가 커밋까지 끝난 뒤에만 XACK한다 — 순서가 바뀌면 "ACK는 됐는데
        // 크래시로 DB엔 없는" 영구 유실이 생긴다. 여기서 예외를 안 잡는 이유도 같다:
        // 실패하면 버퍼/PEL이 그대로 남아 다음 tick에 재시도된다. 성공 알림 큐잉은
        // saveBatch 안에서 같은 트랜잭션으로 처리된다(outbox).
        repository.saveBatch(couponId, events);

        String[] recordIds = buffer.records.stream()
                .map(record -> record.getId().getValue())
                .toArray(String[]::new);
        acknowledgeAndDelete(streamKey, recordIds);

        buffer.records.clear();
        buffer.deadline = null;
    }

    /**
     * 이 컨슈머 그룹(coupon-issue-db-sync)이 스트림의 유일한 소비자라, ACK된
     * 엔트리는 더 이상 아무도 읽지 않는다 — XACK만 하고 남겨두면 스트림이
     * 무한정 커지므로 XDEL로 바로 지운다(Issue #39 체크리스트 12번). 소비자가
     * 여러 그룹으로 늘어나면 이 가정이 깨지니 그때는 XTRIM(MINID) 등으로
     * 바꿔야 한다.
     */
    private void acknowledgeAndDelete(String streamKey, String[] recordIds) {
        redisTemplate.<String, String>opsForStream().acknowledge(streamKey, GROUP_NAME, recordIds);
        redisTemplate.<String, String>opsForStream().delete(streamKey, recordIds);
    }

    private CouponIssueSyncEvent parse(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        return new CouponIssueSyncEvent(
                record.getId(),
                Long.parseLong(fields.get("couponId")),
                Long.parseLong(fields.get("memberId")),
                fields.get("eventId"),
                toIssuedAt(Long.parseLong(fields.get("reservedAtEpochSecond"))));
    }

    /** Lua가 기록한 epoch초(timezone 없음)를 Asia/Seoul 기준 LocalDateTime으로 되돌린다. */
    private LocalDateTime toIssuedAt(long reservedAtEpochSecond) {
        return Instant.ofEpochSecond(reservedAtEpochSecond)
                .atZone(COUPON_TIME_ZONE)
                .toLocalDateTime();
    }

    /** 지금 누적 중인 배치 상태. */
    private static final class StreamBuffer {
        private final List<MapRecord<String, String, String>> records = new ArrayList<>();

        /** 첫 이벤트가 쌓인 시점 + batchWindowMs. null이면 아직 배치 시작 전. */
        private Instant deadline;
    }
}
