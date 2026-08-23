package com.mocou.issue.sync;

import org.springframework.data.redis.RedisSystemException;
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

    private static final String BUSY_GROUP_ERROR = "BUSYGROUP";

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
        String streamKey = CouponRedisKey.issueStream(couponId);

        try {
            redisTemplate.opsForStream().createGroup(
                    streamKey,
                    ReadOffset.from("0"),
                    GROUP_NAME);

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
}
