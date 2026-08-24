package com.mocou.issue.initialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponRedisInitializationServiceIntegrationTest
        extends CouponRedisInitializationServiceIntegrationTestSupport {

    @Test
    @DisplayName("DB 재고와 발급 시간을 Redis에 초기화한다")
    void initializesRedisFromDatabase() {
        insertCoupon();
        insertCouponStock(1000);

        CouponRedisInitializationResult result =
                service.initialize(COUPON_ID);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult.INITIALIZED);
        assertThat(currentStock()).isEqualTo("1000");
        assertThat(currentMetadata())
                .containsEntry(
                        "openAtEpochSecond",
                        Long.toString(
                                expectedOpenAtEpochSecond()))
                .containsEntry(
                        "closeAtEpochSecond",
                        Long.toString(
                                expectedCloseAtEpochSecond()));
    }

    @Test
    @DisplayName("반복 초기화해도 차감된 Redis 재고를 덮어쓰지 않는다")
    void doesNotOverwriteDecrementedStock() {
        insertCoupon();
        insertCouponStock(1000);

        service.initialize(COUPON_ID);
        redisTemplate.opsForValue().set(stockKey(), "700");

        CouponRedisInitializationResult result =
                service.initialize(COUPON_ID);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult
                                .ALREADY_INITIALIZED);
        assertThat(currentStock()).isEqualTo("700");
    }

    @Test
    @DisplayName("쿠폰이 존재하지 않으면 Redis Key를 생성하지 않는다")
    void doesNotInitializeMissingCoupon() {
        assertThatThrownBy(() ->
                service.initialize(COUPON_ID))
                .isInstanceOf(
                        CouponRedisInitializationDataNotFoundException.class);

        assertThat(redisTemplate.hasKey(stockKey())).isFalse();
        assertThat(redisTemplate.hasKey(metadataKey())).isFalse();
    }

    @Test
    @DisplayName("쿠폰 재고가 존재하지 않으면 Redis Key를 생성하지 않는다")
    void doesNotInitializeCouponWithoutStock() {
        insertCoupon();

        assertThatThrownBy(() ->
                service.initialize(COUPON_ID))
                .isInstanceOf(
                        CouponRedisInitializationDataNotFoundException.class);

        assertThat(redisTemplate.hasKey(stockKey())).isFalse();
        assertThat(redisTemplate.hasKey(metadataKey())).isFalse();
    }

    @Test
    @DisplayName("Redis Key가 일부만 존재하면 불완전 상태를 반환한다")
    void reportsInconsistentRedisState() {
        insertCoupon();
        insertCouponStock(1000);
        redisTemplate.opsForValue().set(stockKey(), "500");

        CouponRedisInitializationResult result =
                service.initialize(COUPON_ID);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult
                                .INCONSISTENT_STATE);
        assertThat(currentStock()).isEqualTo("500");
        assertThat(redisTemplate.hasKey(metadataKey()))
                .isFalse();
    }

    @Test
    @DisplayName("0 이하의 쿠폰 ID는 DB 조회 전에 거부한다")
    void rejectsNonPositiveCouponId() {
        assertThatThrownBy(() ->
                service.initialize(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("couponId는 양수여야 합니다.");

        assertThat(redisTemplate.keys("coupon:*")).isEmpty();
    }
}