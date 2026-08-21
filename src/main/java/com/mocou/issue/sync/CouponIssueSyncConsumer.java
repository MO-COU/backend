package com.mocou.issue.sync;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mocou.issue.CouponRedisKey;

import lombok.RequiredArgsConstructor;

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
@Component
@ConditionalOnProperty(prefix = "mocou.issue.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponIssueSyncConsumer {

    private static final String GROUP_NAME = RedisCouponIssueSyncGateway.GROUP_NAME;
    // 인스턴스가 하나뿐인 MVP라 고정값 — 여러 인스턴스를 띄우면 인스턴스마다 달라야 함.
    private static final String CONSUMER_NAME = "sync-worker-1";
    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final StringRedisTemplate redisTemplate;
    private final RedisCouponIssueSyncGateway syncGateway;
    private final CouponIssueSyncRepository repository;
    private final CouponIssueSyncProperties properties;

    private final StreamBuffer buffer = new StreamBuffer();
    // ensureConsumerGroup을 이미 보장해준 couponId. 매 틱 부르면 낭비라 "바뀔 때만" 확인.
    private Long groupEnsuredForCouponId;

    // fixedDelay라 consume()은 절대 자기 자신과 동시에 두 번 안 돈다 — 상태 필드 동시성 걱정 없음.
    @Scheduled(fixedDelayString = "${mocou.issue.sync.poll-interval-ms:10}")
    public void consume() {
        List<Long> openCouponIds = repository.findOpenCouponIds();
        if (openCouponIds.isEmpty()) {
            return;
        }

        pollOnce(openCouponIds.getFirst());
    }

    /** 배치를 완성하는 게 아니라 "한 스텝 진행"시킨다 — 조건이 찰 때까지 여러 번 불린다. */
    private void pollOnce(long couponId) {
        String streamKey = CouponRedisKey.issueStream(couponId);
        ensureConsumerGroup(couponId);

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
    }

    /** XREADGROUP은 그룹이 없으면 NOGROUP 에러를 던지므로 미리 보장해둔다. */
    private void ensureConsumerGroup(long couponId) {
        if (groupEnsuredForCouponId != null && groupEnsuredForCouponId == couponId) {
            return;
        }

        syncGateway.ensureConsumerGroup(couponId);
        groupEnsuredForCouponId = couponId;
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
        repository.saveBatch(couponId, events);

        String[] recordIds = buffer.records.stream()
                .map(record -> record.getId().getValue())
                .toArray(String[]::new);
        redisTemplate.<String, String>opsForStream().acknowledge(streamKey, GROUP_NAME, recordIds);

        buffer.records.clear();
        buffer.deadline = null;
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
