package com.mocou.loadtest;

import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Redis 발급 결과가 DB에 모두 들어갈 때까지 확인함. */
@Slf4j
@Component
public class LoadTestDbSyncMonitor {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public LoadTestDbSyncMonitor(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public void waitUntilComplete(long couponId, int expectedIssuedCount)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        SyncState state = readState(couponId);
        while (!state.isComplete(expectedIssuedCount) && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            state = readState(couponId);
        }
        if (!state.isComplete(expectedIssuedCount)) {
            throw new IllegalStateException(
                    ("DB 적재 완료 대기 시간 초과: couponId=%d, expected=%d, db=%d, stream=%d, pending=%d, "
                                    + "dlqStream=%d, dlqPending=%d")
                            .formatted(
                                    couponId,
                                    expectedIssuedCount,
                                    state.dbIssuedCount(),
                                    state.streamSize(),
                                    state.pendingCount(),
                                    state.dlqStreamSize(),
                                    state.dlqPendingCount()));
        }
        log.info(
                "DB 적재 완료: couponId={}, issuedCount={}", couponId, expectedIssuedCount);
    }

    private SyncState readState(long couponId) {
        Long dbIssuedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?",
                        Long.class,
                        couponId);
        String streamKey = CouponRedisKey.issueStream(couponId);
        Long streamSize = redisTemplate.opsForStream().size(streamKey);
        String dlqStreamKey = CouponRedisKey.issueDlqStream(couponId);
        Long dlqStreamSize = redisTemplate.opsForStream().size(dlqStreamKey);
        return new SyncState(
                dbIssuedCount == null ? 0 : dbIssuedCount,
                streamSize == null ? 0 : streamSize,
                pendingCount(streamKey, RedisCouponIssueSyncGateway.GROUP_NAME),
                dlqStreamSize == null ? 0 : dlqStreamSize,
                pendingCount(dlqStreamKey, RedisCouponIssueSyncGateway.DLQ_GROUP_NAME));
    }

    /** 그룹이 없으면 처리 중인 이벤트도 없음. */
    private long pendingCount(String streamKey, String groupName) {
        try {
            var pending = redisTemplate.opsForStream().pending(streamKey, groupName);
            return pending == null ? 0 : pending.getTotalPendingMessages();
        } catch (DataAccessException groupNotFound) {
            return 0;
        }
    }

    private record SyncState(
            long dbIssuedCount, long streamSize, long pendingCount, long dlqStreamSize, long dlqPendingCount) {

        /**
         * DLQ까지 완전히 비어야 완료로 본다 — 부하 테스트 중 DLQ로 넘어간 건이 있다면 그 자체가
         * 이상 신호이므로, 조용히 무시하지 않고 대기시간 초과로 드러내는 편이 맞다.
         */
        private boolean isComplete(int expectedIssuedCount) {
            return dbIssuedCount == expectedIssuedCount
                    && streamSize == 0
                    && pendingCount == 0
                    && dlqStreamSize == 0
                    && dlqPendingCount == 0;
        }
    }
}
