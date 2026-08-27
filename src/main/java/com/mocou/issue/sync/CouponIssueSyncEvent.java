package com.mocou.issue.sync;

import java.time.LocalDateTime;

import org.springframework.data.redis.connection.stream.RecordId;

/**
 * Stream의 {@code MapRecord}(eventId, couponId, memberId, issueSequence,
 * remainingAtIssue, reservedAtEpochSecond)를 파싱한 결과. {@code recordId}는 DB
 * 반영과 무관하지만, 실패 로그·재시도(F-ISS-002) 단계에서 "어느 Stream 엔트리가
 * 문제였는지" 추적하려고 들고 다닌다.
 *
 * <p>{@code issueSequence}/{@code remainingAtIssue}는 Redis Lua(reserve-and-append-event)가
 * 예약 성공 순간 원자적으로 확정한 값을 그대로 옮겨 담는다 - coupon_issue의 같은 이름
 * 컬럼(V11)에 그대로 저장된다.
 */
public record CouponIssueSyncEvent(
        RecordId recordId,
        long couponId,
        long memberId,
        String eventId,
        long issueSequence,
        long remainingAtIssue,
        LocalDateTime issuedAt) {
}
