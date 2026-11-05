package com.mocou.consistency;

import com.mocou.consistency.RedisSyncBaseline.CouponState;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 쪽 동기화 상태를 읽는다.
 *
 * <p>컨슈머가 DB 커밋 뒤 {@code XACK}과 {@code XDEL}을 함께 하므로, 스트림의 남은 엔트리와 미확인 건이 모두 0이면 처리가
 * 끝난 것이다. DLQ에서 복구를 기다리는 건도 같은 이유로 본다. 그룹 이름은 컨슈머가 쓰는 상수를 그대로 가리킨다.
 */
@Component
@RequiredArgsConstructor
public class RedisSyncBaselineReader {

    private static final String OPEN_COUPON_SQL =
            "SELECT coupon_id FROM coupon WHERE status = 'OPEN' ORDER BY coupon_id";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RedisCouponIssueSyncGateway syncGateway;

    /**
     * 스냅샷을 열기 전에 부른다. 읽지 못하면 사유를 담아 돌려준다. Redis를 보지 않는 규칙은 그대로 판정한다.
     */
    public RedisSyncBaseline capture() {
        try {
            Map<Long, CouponState> states = new LinkedHashMap<>();
            for (long couponId : jdbcTemplate.queryForList(OPEN_COUPON_SQL, Long.class)) {
                states.put(couponId, new CouponState(pendingDescription(couponId), issueSequence(couponId)));
            }
            return RedisSyncBaseline.of(states);
        } catch (RuntimeException e) {
            return RedisSyncBaseline.failed(e.getClass().getSimpleName());
        }
    }

    /**
     * 아직 DB로 넘어가지 않은 발급이 남아 있는지 본다.
     *
     * @return 남아 있으면 사유, 없으면 {@code null}
     */
    public String pendingDescription(long couponId) {
        String streamKey = CouponRedisKey.issueStream(couponId);
        Long unprocessed = redisTemplate.opsForStream().size(streamKey);
        if (unprocessed != null && unprocessed > 0) {
            return "쿠폰 %d의 발급 이벤트 %d건이 아직 DB로 동기화되지 않았다. 동기화가 끝난 뒤 다시 검증해야 한다"
                    .formatted(couponId, unprocessed);
        }

        long unacknowledged = syncGateway.pendingCount(streamKey, RedisCouponIssueSyncGateway.GROUP_NAME);
        if (unacknowledged > 0) {
            return "쿠폰 %d의 발급 이벤트 %d건이 컨슈머에서 처리 중이다. 동기화가 끝난 뒤 다시 검증해야 한다"
                    .formatted(couponId, unacknowledged);
        }

        String dlqStreamKey = CouponRedisKey.issueDlqStream(couponId);
        Long dlqUnprocessed = redisTemplate.opsForStream().size(dlqStreamKey);
        if (dlqUnprocessed != null && dlqUnprocessed > 0) {
            return "쿠폰 %d의 발급 이벤트 %d건이 DLQ에서 아직 복구를 시도하는 중이다. 복구가 끝난 뒤 다시 검증해야 한다"
                    .formatted(couponId, dlqUnprocessed);
        }
        long dlqUnacknowledged =
                syncGateway.pendingCount(dlqStreamKey, RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
        if (dlqUnacknowledged > 0) {
            return "쿠폰 %d의 발급 이벤트 %d건이 DLQ 복구 컨슈머에서 처리 중이다. 복구가 끝난 뒤 다시 검증해야 한다"
                    .formatted(couponId, dlqUnacknowledged);
        }
        return null;
    }

    /** 예약이 성공할 때마다 Lua가 {@code INCR}하는 순번. 값이 바뀌었다면 그 사이 발급이 있었다. */
    public String issueSequence(long couponId) {
        return redisTemplate.opsForValue().get(CouponRedisKey.issueSequence(couponId));
    }
}
