package com.mocou.issue.initialization;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.mocou.issue.CouponRedisKey;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisCouponInitializationGateway {

    private static final long INITIALIZED = 1L;
    private static final long ALREADY_INITIALIZED = 0L;
    private static final long INCONSISTENT_STATE = -1L;

    private static final RedisScript<Long> INITIALIZE_SCRIPT =
            RedisScript.of(
                    new ClassPathResource(
                            "scripts/redis/initialize-coupon.lua"),
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    public CouponRedisInitializationResult initialize(
            long couponId,
            int remainingQuantity,
            long openAtEpochSecond,
            long closeAtEpochSecond
    ) {
        validate(
                remainingQuantity,
                openAtEpochSecond,
                closeAtEpochSecond);

        Long result = redisTemplate.execute(
                INITIALIZE_SCRIPT,
                createKeys(couponId),
                Integer.toString(remainingQuantity),
                Long.toString(openAtEpochSecond),
                Long.toString(closeAtEpochSecond));

        if (result == null) {
            throw new IllegalStateException("Redis initialization script returned null.");
        }

        return toInitializationResult(result);
    }

    private List<String> createKeys(long couponId) {
        return List.of(
                CouponRedisKey.stock(couponId),
                CouponRedisKey.metadata(couponId));
    }

    private CouponRedisInitializationResult toInitializationResult(
            long result
    ) {
        if (result == INITIALIZED) {
            return CouponRedisInitializationResult.INITIALIZED;
        }

        if (result == ALREADY_INITIALIZED) {
            return CouponRedisInitializationResult.ALREADY_INITIALIZED;
        }

        if (result == INCONSISTENT_STATE) {
            return CouponRedisInitializationResult.INCONSISTENT_STATE;
        }

        throw new IllegalStateException("Unexpected Redis initialization result: " + result);
    }

    private void validate(
            int remainingQuantity,
            long openAtEpochSecond,
            long closeAtEpochSecond
    ) {
        if (remainingQuantity < 0) {
            throw new IllegalArgumentException("잔여 재고는 음수일 수 없습니다.");
        }

        if (openAtEpochSecond >= closeAtEpochSecond) {
            throw new IllegalArgumentException("오픈 시각은 종료 시각보다 이전이어야 합니다.");
        }
    }
}