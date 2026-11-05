package com.mocou.issue.sync;

import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mocou.issue.CouponRedisKey;

import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급 이벤트 Stream을 DB로 동기화하기 위한 Consumer Group을 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RedisCouponIssueSyncGateway {

    public static final String GROUP_NAME = "coupon-issue-db-sync";
    public static final String DLQ_GROUP_NAME = "coupon-issue-dlq-recovery";

    private static final String BUSY_GROUP_ERROR = "BUSYGROUP";
    private static final String NO_GROUP_ERROR = "NOGROUP";

    private final StringRedisTemplate redisTemplate;

    /**
     * couponId에 해당하는 발급 이벤트 Stream에 DB 동기화용 Consumer Group을
     * 생성한다. Stream이 아직 없으면 함께 생성하고(MKSTREAM), 이미 그룹이
     * 존재하면 아무 것도 하지 않는다(멱등).
     *
     * <p>ReadOffset은 "0"으로 지정해, 그룹 생성 이전에 이미 쌓여 있던
     * 이벤트도 이후 XREADGROUP으로 읽을 수 있게 한다.
     */
    public CouponIssueSyncGroupResult ensureConsumerGroup(long couponId) {
        return ensureGroup(CouponRedisKey.issueStream(couponId), GROUP_NAME);
    }

    /**
     * couponId에 해당하는 DLQ Stream에 복구용 Consumer Group을 생성한다.
     * 메인 스트림용과 동일한 멱등 규칙을 따른다.
     */
    public CouponIssueSyncGroupResult ensureDlqConsumerGroup(long couponId) {
        return ensureGroup(CouponRedisKey.issueDlqStream(couponId), DLQ_GROUP_NAME);
    }

    /**
     * 그룹이 읽어 갔지만 아직 ACK하지 않은 이벤트 수. 그룹이 없으면 읽어 간 이벤트도 없으므로 0이다.
     * 그 밖의 Redis 오류는 0으로 바꾸지 않고 던진다.
     */
    public long pendingCount(String streamKey, String groupName) {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, groupName);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RedisSystemException e) {
            if (isNoGroupError(e)) {
                return 0;
            }
            throw e;
        }
    }

    private CouponIssueSyncGroupResult ensureGroup(String streamKey, String groupName) {
        try {
            redisTemplate.opsForStream().createGroup(
                    streamKey,
                    ReadOffset.from("0"),
                    groupName);

            return CouponIssueSyncGroupResult.CREATED;
        } catch (RedisSystemException e) {
            if (isBusyGroupError(e)) {
                return CouponIssueSyncGroupResult.ALREADY_EXISTS;
            }

            throw e;
        }
    }

    private boolean isBusyGroupError(RedisSystemException e) {
        Throwable cause = e.getMostSpecificCause();

        return cause.getMessage() != null
                && cause.getMessage().contains(BUSY_GROUP_ERROR);
    }

    private boolean isNoGroupError(RedisSystemException e) {
        Throwable cause = e.getMostSpecificCause();

        return cause.getMessage() != null
                && cause.getMessage().contains(NO_GROUP_ERROR);
    }
}
