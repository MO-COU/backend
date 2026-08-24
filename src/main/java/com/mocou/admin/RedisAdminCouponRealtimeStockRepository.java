package com.mocou.admin;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import java.util.OptionalInt;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisAdminCouponRealtimeStockRepository
        implements AdminCouponRealtimeStockRepository {

    private final StringRedisTemplate redisTemplate;

    public RedisAdminCouponRealtimeStockRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public OptionalInt findRemainingQuantity(long couponId) {
        try {
            String value =
                    redisTemplate.opsForValue().get(CouponRedisKey.stock(couponId));
            if (value == null) {
                return OptionalInt.empty();
            }

            int remainingQuantity = Integer.parseInt(value);
            if (remainingQuantity < 0) {
                throw new NumberFormatException("negative stock");
            }
            return OptionalInt.of(remainingQuantity);
        } catch (DataAccessException | NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "실시간 쿠폰 재고를 조회할 수 없습니다");
        }
    }
}
