package com.mocou.coupon;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class JdbcCouponRoundRepository implements CouponRoundRepository {

    private static final String NEXT_ROUND_NUMBER_SQL =
            "SELECT COALESCE(MAX(coupon_id), 0) + 1 FROM coupon";

    /** 상태는 항상 {@code OPEN}이다. 오픈 여부 판정은 Redis가 {@code open_at}으로 한다. */
    private static final String INSERT_COUPON_SQL =
            """
            INSERT INTO coupon (coupon_id, name, open_at, close_at, status)
            VALUES (:couponId, :name, :openAt, :closeAt, 'OPEN')
            """;

    /** 발급 이력이 없는 새 회차라 잔여 재고가 총 재고와 같다. */
    private static final String INSERT_STOCK_SQL =
            """
            INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)
            VALUES (:couponId, :totalQuantity, :totalQuantity)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public long nextRoundNumber() {
        Long next = jdbcTemplate.queryForObject(NEXT_ROUND_NUMBER_SQL, Map.of(), Long.class);
        return next == null ? 1 : next;
    }

    @Override
    public void insertRound(
            long couponId,
            String name,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            int totalQuantity) {
        jdbcTemplate.update(
                INSERT_COUPON_SQL,
                Map.of(
                        "couponId", couponId,
                        "name", name,
                        "openAt", openAt,
                        "closeAt", closeAt));
        jdbcTemplate.update(
                INSERT_STOCK_SQL, Map.of("couponId", couponId, "totalQuantity", totalQuantity));
    }
}
