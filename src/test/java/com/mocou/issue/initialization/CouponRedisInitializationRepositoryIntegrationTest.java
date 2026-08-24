package com.mocou.issue.initialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mocou.support.MySqlContainerTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponRedisInitializationRepositoryIntegrationTest
        extends MySqlContainerTest {

    private static final long COUPON_ID = 1001L;
    private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 20, 10, 0);
    private static final LocalDateTime CLOSE_AT = LocalDateTime.of(2026, 8, 20, 11, 0);

    @Autowired
    private CouponRedisInitializationRepository repository;

    @Test
    @DisplayName("쿠폰과 재고를 Redis 초기화 데이터로 조회한다")
    void findsInitializationData() {
        insertCoupon();
        insertCouponStock(1000);

        CouponRedisInitializationData result =
                repository.findByCouponId(COUPON_ID).orElseThrow();

        assertThat(result.couponId()).isEqualTo(COUPON_ID);
        assertThat(result.remainingQuantity()).isEqualTo(1000);
        assertThat(result.openAt()).isEqualTo(OPEN_AT);
        assertThat(result.closeAt()).isEqualTo(CLOSE_AT);
    }

    @Test
    @DisplayName("쿠폰이 존재하지 않으면 빈 결과를 반환한다")
    void returnsEmptyWhenCouponDoesNotExist() {
        assertThat(repository.findByCouponId(COUPON_ID)).isEmpty();
    }

    @Test
    @DisplayName("쿠폰 재고가 존재하지 않으면 빈 결과를 반환한다")
    void returnsEmptyWhenCouponStockDoesNotExist() {
        insertCoupon();

        assertThat(repository.findByCouponId(COUPON_ID)).isEmpty();
    }

    private void insertCoupon() {
        jdbcTemplate.update(
                """
                INSERT INTO coupon (
                    coupon_id,
                    name,
                    open_at,
                    close_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                COUPON_ID,
                "Redis 초기화 테스트 쿠폰",
                OPEN_AT,
                CLOSE_AT,
                "READY");
    }

    private void insertCouponStock(int remainingQuantity) {
        jdbcTemplate.update(
                """
                INSERT INTO coupon_stock (
                    coupon_id,
                    total_quantity,
                    remaining_quantity
                )
                VALUES (?, ?, ?)
                """,
                COUPON_ID,
                1000,
                remainingQuantity);
    }
}