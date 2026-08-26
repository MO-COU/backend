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

import com.mocou.coupon.CouponStatusChangedEvent;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.RedisCouponIssueGateway;
import com.mocou.notification.NotificationSender;
import com.mocou.notification.NotificationType;

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
 * <p>동시에 열려있는 쿠폰이 여러 개인 경우는 다루지 않는다(1차 MVP는 쿠폰
 * 하나 기준) — {@link #consume()}은 여러 건이 와도 하나만 처리한다.
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
    private final NotificationSender notificationSender;
    private final CouponIssueSyncProperties properties;

    private final StreamBuffer buffer = new StreamBuffer();
    // ensureConsumerGroup을 이미 보장해준 couponId. 매 틱 부르면 낭비라 "바뀔 때만" 확인.
    private Long groupEnsuredForCouponId;
    // XPENDING을 다시 확인해도 되는 시점. 매 틱마다 하면 낭비라 pendingCheckIntervalMs 간격으로 제한.
    private Instant nextPendingCheckAt = Instant.EPOCH;
    // 매 틱(10ms)마다 DB를 조회하면 그 자체가 병목이라, 시작 시 한 번만 채우고 이후는
    // CouponStatusChangedEvent(관리자 API가 상태를 바꿀 때 발행)를 받을 때만 갱신한다.
    // @Scheduled 스레드와 이벤트를 발행하는 쪽(관리자 API 요청 스레드)이 다를 수 있어
    // volatile로 가시성을 보장한다 — 참조를 통째로 교체만 하니 이 정도로 충분하다.
    private volatile List<Long> openCouponIds = List.of();

    /** 빈이 뜰 때 한 번만 DB를 조회해 캐시를 채운다 — "최초 실행 시 한 번만" 조회. */
    @PostConstruct
    void initOpenCouponIds() {
        refreshOpenCouponIds();
    }

    /** 관리자 API 등이 coupon.status를 바꾸고 발행하는 이벤트를 받아 캐시를 다시 채운다. */
    @EventListener
    void onCouponStatusChanged(CouponStatusChangedEvent event) {
        refreshOpenCouponIds();
    }

    private void refreshOpenCouponIds() {
        openCouponIds = repository.findOpenCouponIds();
    }

    // fixedDelay라 consume()은 절대 자기 자신과 동시에 두 번 안 돈다 — 상태 필드 동시성 걱정 없음.
    @Scheduled(fixedDelayString = "${mocou.issue.sync.poll-interval-ms:10}")
    public void consume() {
        List<Long> couponIds = openCouponIds;
        if (couponIds.isEmpty()) {
            return;
        }

        pollOnce(couponIds.getFirst());
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
     * <p>그룹을 실제로 (다시) 만들어야 하는 시점(최초 실행, 또는 NOGROUP 감지로 캐시가
     * 무효화된 뒤)은 캐시가 최신이 아니었을 수 있다는 신호이기도 하다 — {@code openCouponIds}는
     * {@code CouponStatusChangedEvent}로만 갱신되는데, 그 이벤트가 유실되거나 재시작 시점에
     * DB 상태 반영이 늦었을 수 있다. 그래서 그룹 생성 직전에 OPEN 쿠폰 목록도 한 번 더
     * 조회해서 같이 최신화한다. 그룹 생성 자체가 드문(캐시 히트가 대부분인) 이벤트라 매
     * tick DB를 두드리는 것과 달리 부담이 없다.
     */
    private void ensureConsumerGroup(long couponId) {
        if (groupEnsuredForCouponId != null && groupEnsuredForCouponId == couponId) {
            return;
        }

        refreshOpenCouponIds();
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
        // 실패하면 버퍼/PEL이 그대로 남아 다음 tick에 재시도된다.
        List<CouponIssueSyncEvent> savedEvents = repository.saveBatch(couponId, events);
        notifyIssueSuccess(savedEvents);

        String[] recordIds = buffer.records.stream()
                .map(record -> record.getId().getValue())
                .toArray(String[]::new);
        acknowledgeAndDelete(streamKey, recordIds);

        buffer.records.clear();
        buffer.deadline = null;
    }

    /**
     * saveBatch가 커밋까지 끝낸(=실제로 새로 저장된) 이벤트에 대해서만 발급 성공
     * 알림을 보낸다. 재전달돼 skip된 이벤트는 savedEvents에 없으므로 중복 알림이
     * 안 나간다.
     */
    private void notifyIssueSuccess(List<CouponIssueSyncEvent> savedEvents) {
        for (CouponIssueSyncEvent event : savedEvents) {
            notificationSender.notifyMember(NotificationType.ISSUE_SUCCESS, event.couponId(), event.memberId());
        }
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
