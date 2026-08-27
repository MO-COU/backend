package com.mocou.issue.sync;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.RedisCouponIssueGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link CouponIssueSyncConsumer}가 재시도 한도를 넘겨 DLQ로 옮긴 발급 이벤트를
 * 다시 처리한다.
 *
 * <p>메인 컨슈머는 빠른 재시도(기본 10초 간격, 최대 3회)만 담당하고, 그래도 안 되면
 * 여기(기본 20초 간격, 최대 5회)로 넘어와 DB가 회복될 때까지 좀 더 여유 있게
 * 재시도한다. 여기서마저 한도를 넘기면 그때 비로소 Redis 재고를 보상하고
 * {@code issue_failure_log}에 최종 실패로 남긴다 — 그 전까지는 회원의 Redis 예약을
 * 그대로 유지해, 복구가 성공하면 애초에 보상이 필요 없게 한다.
 *
 * <p>활성 쿠폰은 메인 컨슈머와 동일하게 {@link ActiveCouponIdHolder} 하나를 공유해서
 * 본다 — "이전 쿠폰은 DLQ까지 전부 끝나야 다음 쿠폰이 시작된다"는 운영 정책상 두
 * 컨슈머가 서로 다른 쿠폰을 볼 상황 자체가 없기 때문이다.
 *
 * <p>메인 컨슈머와 달리 처리량 압박이 없어 "N건 또는 M초" 배치 창을 두지 않는다 —
 * 매 tick 새 항목과 재시도 대상 항목을 모아 바로 저장을 시도한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mocou.issue.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponIssueDlqRecoveryConsumer {

    private static final String GROUP_NAME = RedisCouponIssueSyncGateway.DLQ_GROUP_NAME;
    // 인스턴스가 하나뿐인 MVP라 고정값 — 메인 컨슈머와 이름만 다르면 됨(그룹/스트림이 다르므로 충돌 없음).
    private static final String CONSUMER_NAME = "dlq-recovery-worker-1";
    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");
    private static final String NO_GROUP_ERROR = "NOGROUP";
    // 배치 창이 없어 한 틱에 읽을 수 있는 상한만 두면 된다 — 메인 스트림의 기본 chunkSize와 동일한 값.
    private static final int READ_COUNT = 100;

    private final RedisCouponIssueSyncGateway syncGateway;
    private final RedisCouponIssueGateway issueGateway;
    private final CouponIssueSyncRepository repository;
    private final CouponIssueSyncProperties properties;
    private final RedisStreamGroupRecovery streamGroupRecovery;
    private final ActiveCouponIdHolder activeCouponIdHolder;

    private Long groupEnsuredForCouponId;
    private Long polledCouponId;

    @Scheduled(fixedDelayString = "${mocou.issue.sync.dlq.poll-interval-ms:20000}")
    public void recover() {
        Long couponId = activeCouponIdHolder.get();
        if (couponId == null) {
            return;
        }
        if (!couponId.equals(polledCouponId)) {
            // 메인 컨슈머의 switchActiveCoupon과 같은 이유 — 이전 쿠폰의 그룹 캐시를 버린다.
            groupEnsuredForCouponId = null;
            polledCouponId = couponId;
        }

        pollOnce(couponId);
    }

    private void pollOnce(long couponId) {
        String streamKey = CouponRedisKey.issueDlqStream(couponId);
        ensureConsumerGroup(couponId, streamKey);

        try {
            reclaimAndRetry(couponId, streamKey);
        } catch (RedisSystemException exception) {
            if (isNoGroupError(exception)) {
                log.warn(
                        "DLQ 컨슈머 그룹이 사라진 것을 감지했다. 캐시를 무효화해 다음 tick에 재생성한다. couponId={}",
                        couponId);
                groupEnsuredForCouponId = null;
            }
            throw exception;
        }
    }

    private void ensureConsumerGroup(long couponId, String streamKey) {
        if (groupEnsuredForCouponId != null && groupEnsuredForCouponId == couponId) {
            return;
        }

        syncGateway.ensureDlqConsumerGroup(couponId);
        groupEnsuredForCouponId = couponId;
    }

    private boolean isNoGroupError(RedisSystemException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(NO_GROUP_ERROR);
    }

    /**
     * DLQ에 새로 들어온 항목(XREADGROUP)과, 이미 한 번 시도했지만 아직 최종 한도
     * 안쪽인 항목(XCLAIM)을 모아 저장을 재시도한다. 최종 한도를 넘긴 항목은
     * {@link #finalizeExhausted}로 넘긴다.
     */
    private void reclaimAndRetry(long couponId, String streamKey) {
        List<MapRecord<String, String, String>> fresh =
                streamGroupRecovery.readNext(streamKey, GROUP_NAME, CONSUMER_NAME, READ_COUNT, 0);
        List<MapRecord<String, String, String>> toRetry = new ArrayList<>(fresh == null ? List.of() : fresh);

        PendingMessages pending = streamGroupRecovery.pending(streamKey, GROUP_NAME, READ_COUNT);
        if (pending != null && !pending.isEmpty()) {
            PendingEntryClassifier.Result result = PendingEntryClassifier.classify(
                    pending.stream().toList(),
                    properties.getDlqPendingMinIdleMs(),
                    properties.getDlqMaxDeliveryCount());

            toRetry.addAll(claim(streamKey, result.retryableIds()));
            finalizeExhausted(streamKey, claim(streamKey, result.exhaustedIds()));
        }

        retryInsert(couponId, streamKey, toRetry);
    }

    /**
     * 실제 {@code coupon_issue} insert를 재시도한다. 실패하면(DB가 아직 안 살아났으면)
     * 예외가 그대로 던져져 ACK를 하지 않으므로, 이 배치는 DLQ의 PEL에 남아 다음
     * tick(reclaim)에 다시 시도된다 — 메인 컨슈머의 flushIfDue와 같은 안전장치다.
     */
    private void retryInsert(long couponId, String streamKey, List<MapRecord<String, String, String>> records) {
        if (records.isEmpty()) {
            return;
        }

        List<CouponIssueSyncEvent> events = records.stream().map(this::parse).toList();
        repository.saveBatch(couponId, events);

        String[] recordIds = records.stream()
                .map(record -> record.getId().getValue())
                .toArray(String[]::new);
        streamGroupRecovery.acknowledgeAndDelete(streamKey, GROUP_NAME, recordIds);
    }

    /**
     * DLQ 복구마저 최종 한도(dlqMaxDeliveryCount)를 넘긴 엔트리를 처리한다. 여기서
     * 비로소 Redis 재고를 보상하고 issue_failure_log에 최종 실패로 남긴다.
     * compensate는 멱등이라 이 메서드 도중 예외가 나 다음 tick에 재시도돼도 재고를
     * 이중으로 원복하지 않는다.
     */
    private void finalizeExhausted(String streamKey, List<MapRecord<String, String, String>> claimed) {
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
        streamGroupRecovery.acknowledgeAndDelete(streamKey, GROUP_NAME, recordIds);
    }

    private List<MapRecord<String, String, String>> claim(String streamKey, List<String> ids) {
        return streamGroupRecovery.claim(
                streamKey, GROUP_NAME, CONSUMER_NAME, properties.getDlqPendingMinIdleMs(), ids);
    }

    private CouponIssueSyncEvent parse(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        return new CouponIssueSyncEvent(
                record.getId(),
                Long.parseLong(fields.get("couponId")),
                Long.parseLong(fields.get("memberId")),
                fields.get("eventId"),
                Long.parseLong(fields.get("issueSequence")),
                Long.parseLong(fields.get("remainingAtIssue")),
                toIssuedAt(Long.parseLong(fields.get("reservedAtEpochSecond"))));
    }

    private LocalDateTime toIssuedAt(long reservedAtEpochSecond) {
        return Instant.ofEpochSecond(reservedAtEpochSecond)
                .atZone(COUPON_TIME_ZONE)
                .toLocalDateTime();
    }
}
