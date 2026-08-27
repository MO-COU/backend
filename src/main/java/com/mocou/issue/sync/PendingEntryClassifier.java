package com.mocou.issue.sync;

import java.util.List;

import org.springframework.data.redis.connection.stream.PendingMessage;

/**
 * PEL 엔트리를 minIdle 경과 여부와 재시도 한도 기준으로 분류하는 순수 판정 로직.
 *
 * <p>{@link CouponIssueSyncConsumer}(메인 스트림)와 {@link CouponIssueDlqRecoveryConsumer}
 * (DLQ 복구)가 같은 판정 기준(오래 미확인 상태인 것만 보고, 배달 횟수로 재시도/포기를
 * 가른다)을 쓰지만 minIdle·한도 값만 다르므로 여기 하나로 공통화한다.
 */
final class PendingEntryClassifier {

    private PendingEntryClassifier() {
    }

    record Result(List<String> retryableIds, List<String> exhaustedIds) {
    }

    /**
     * @param minIdleMs 이 시간보다 짧게 대기 중인 엔트리는 아직 정상 처리 중일 수 있으므로 건드리지 않는다.
     * @param maxDeliveryCount 이 값을 넘겨 배달된 엔트리는 재시도 대신 포기(exhausted) 대상이다.
     */
    static Result classify(List<PendingMessage> pending, long minIdleMs, int maxDeliveryCount) {
        List<PendingMessage> stale = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().toMillis() >= minIdleMs)
                .toList();

        List<String> retryableIds = stale.stream()
                .filter(message -> message.getTotalDeliveryCount() <= maxDeliveryCount)
                .map(PendingMessage::getIdAsString)
                .toList();
        List<String> exhaustedIds = stale.stream()
                .filter(message -> message.getTotalDeliveryCount() > maxDeliveryCount)
                .map(PendingMessage::getIdAsString)
                .toList();

        return new Result(retryableIds, exhaustedIds);
    }
}
