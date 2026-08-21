package com.mocou.issue.sync;

import java.time.LocalDateTime;

import org.springframework.data.redis.connection.stream.RecordId;

/**
 * Stream의 {@code MapRecord}(eventId, couponId, memberId, reservedAtEpochSecond)를
 * 파싱한 결과. {@code recordId}는 DB 반영과 무관하지만, 실패 로그·재시도(F-ISS-002)
 * 단계에서 "어느 Stream 엔트리가 문제였는지" 추적하려고 들고 다닌다.
 */
public record CouponIssueSyncEvent(
        RecordId recordId,
        long couponId,
        long memberId,
        String eventId,
        LocalDateTime issuedAt) {
}
