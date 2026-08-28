package com.mocou.issue.sync;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * Stream의 {@code MapRecord}(eventId, couponId, memberId, issueSequence,
 * remainingAtIssue, reservedAtEpochSecond)를 {@link CouponIssueSyncEvent}로 파싱한다.
 * 메인 스트림, DLQ, DLQ 최종 실패 스트림이 전부 같은 필드 구성을 쓰므로 공통화했다.
 */
public final class CouponIssueSyncEventParser {

    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private CouponIssueSyncEventParser() {
    }

    public static CouponIssueSyncEvent parse(MapRecord<String, String, String> record) {
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

    /** Lua가 기록한 epoch초(timezone 없음)를 Asia/Seoul 기준 LocalDateTime으로 되돌린다. */
    private static LocalDateTime toIssuedAt(long reservedAtEpochSecond) {
        return Instant.ofEpochSecond(reservedAtEpochSecond)
                .atZone(COUPON_TIME_ZONE)
                .toLocalDateTime();
    }
}
