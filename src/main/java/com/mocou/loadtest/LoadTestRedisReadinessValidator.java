package com.mocou.loadtest;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 실행 전 Redis 상태 확인함. */
@Component
public class LoadTestRedisReadinessValidator {

    private final StringRedisTemplate redisTemplate;

    public LoadTestRedisReadinessValidator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void validate(long couponId, int expectedStock) {
        try {
            validateState(couponId, expectedStock);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Redis 상태를 확인할 수 없습니다");
        }
    }

    private void validateState(long couponId, int expectedStock) {
        String stock = redisTemplate.opsForValue().get(CouponRedisKey.stock(couponId));
        if (stock == null || !stock.equals(Integer.toString(expectedStock))) {
            throw notReady("Redis 재고가 " + expectedStock + "장으로 초기화되지 않았습니다");
        }
        String metadataKey = CouponRedisKey.metadata(couponId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(metadataKey))) {
            throw notReady("Redis 쿠폰 발급 시간 정보가 초기화되지 않았습니다");
        }
        long openAt = readEpochSecond(metadataKey, "openAtEpochSecond");
        long closeAt = readEpochSecond(metadataKey, "closeAtEpochSecond");
        // 발급 시간 정보가 올바른지 확인함.
        if (openAt >= closeAt) {
            throw notReady("Redis 쿠폰 발급 시간 정보가 올바르지 않습니다");
        }

        Long issuedMembers = redisTemplate.opsForSet().size(CouponRedisKey.issuedMembers(couponId));
        Long streamLength = redisTemplate.opsForStream().size(CouponRedisKey.issueStream(couponId));
        Long resultCounts = redisTemplate.opsForHash().size(CouponRedisKey.issueResultCounts(couponId));
        // 이전 실행 값이 남아 있으면 결과 비교 불가.
        if (positive(issuedMembers) || positive(streamLength) || positive(resultCounts)) {
            throw notReady("이전 실행의 Redis 데이터가 남아 있습니다. 해당 회차를 초기화해 주세요");
        }
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    private long readEpochSecond(String metadataKey, String field) {
        Object value = redisTemplate.opsForHash().get(metadataKey, field);
        if (value == null) {
            throw notReady("Redis 쿠폰 발급 시간 정보가 완전하지 않습니다: " + field);
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            throw notReady("Redis 쿠폰 발급 시간 정보가 숫자가 아닙니다: " + field);
        }
    }

    private BusinessException notReady(String message) {
        return new BusinessException(ErrorCode.LOAD_TEST_COUPON_NOT_READY, message);
    }
}
