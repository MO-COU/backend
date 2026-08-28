package com.mocou.admin;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code issue-dlq-failed} Stream(DLQ 복구마저 소진해 최종 실패로 확정된 엔트리)을
 * 그대로 읽는다. 이 스트림은 관리자가 확인하기 전까지 삭제되지 않으므로 XRANGE
 * 전체를 그대로 "실패 목록"으로 볼 수 있다.
 */
@Repository
public class RedisAdminCouponDlqFailureRepository {

    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final StringRedisTemplate redisTemplate;

    public RedisAdminCouponDlqFailureRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<AdminCouponDlqFailure> findFailures(long couponId) {
        try {
            List<MapRecord<String, String, String>> records =
                    redisTemplate
                            .<String, String>opsForStream()
                            .range(CouponRedisKey.issueDlqFailedStream(couponId), Range.unbounded());
            return records == null
                    ? List.of()
                    : records.stream().map(record -> toFailure(couponId, record)).toList();
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "DLQ 실패 목록을 조회할 수 없습니다");
        }
    }

    /**
     * 관리자가 재시도할 항목 하나를 recordId로 지정해 읽는다. 이미 재시도됐거나
     * (failed 스트림에서 이미 지워짐) 잘못된 id면 empty.
     */
    public Optional<MapRecord<String, String, String>> findOne(long couponId, String recordId) {
        try {
            List<MapRecord<String, String, String>> records =
                    redisTemplate
                            .<String, String>opsForStream()
                            .range(
                                    CouponRedisKey.issueDlqFailedStream(couponId),
                                    Range.closed(recordId, recordId));
            return records == null || records.isEmpty()
                    ? Optional.empty()
                    : Optional.of(records.getFirst());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "DLQ 실패 목록을 조회할 수 없습니다");
        }
    }

    /** 재시도(DB 저장)가 끝난 뒤 failed 스트림에서 항목을 제거한다. */
    public void delete(long couponId, String recordId) {
        redisTemplate
                .opsForStream()
                .delete(CouponRedisKey.issueDlqFailedStream(couponId), recordId);
    }

    private AdminCouponDlqFailure toFailure(long couponId, MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        return new AdminCouponDlqFailure(
                record.getId().getValue(),
                couponId,
                Long.parseLong(fields.get("memberId")),
                fields.get("eventId"),
                Long.parseLong(fields.get("issueSequence")),
                Long.parseLong(fields.get("remainingAtIssue")),
                Instant.ofEpochSecond(Long.parseLong(fields.get("reservedAtEpochSecond")))
                        .atZone(COUPON_TIME_ZONE)
                        .toLocalDateTime(),
                null,
                null);
    }
}
