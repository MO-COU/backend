package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisAdminCouponRealtimeStockRepositoryTest {

    private static final long COUPON_ID = 10L;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void returnsRealtimeRemainingQuantity() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(CouponRedisKey.stock(COUPON_ID))).willReturn("1990");
        RedisAdminCouponRealtimeStockRepository repository =
                new RedisAdminCouponRealtimeStockRepository(redisTemplate);

        assertThat(repository.findRemainingQuantity(COUPON_ID)).hasValue(1_990);
    }

    @Test
    void returnsEmptyWhenRedisStockIsNotInitialized() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        RedisAdminCouponRealtimeStockRepository repository =
                new RedisAdminCouponRealtimeStockRepository(redisTemplate);

        assertThat(repository.findRemainingQuantity(COUPON_ID)).isEmpty();
    }

    @Test
    void rejectsInvalidRedisStock() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(CouponRedisKey.stock(COUPON_ID))).willReturn("broken");
        RedisAdminCouponRealtimeStockRepository repository =
                new RedisAdminCouponRealtimeStockRepository(redisTemplate);

        assertThatThrownBy(() -> repository.findRemainingQuantity(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
