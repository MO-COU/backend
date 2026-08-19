package com.mocou.issue.initialization;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JdbcCouponRedisInitializationRepository
        implements CouponRedisInitializationRepository {

    private static final String FIND_BY_COUPON_ID = """
            SELECT
                c.coupon_id,
                cs.remaining_quantity,
                c.open_at,
                c.close_at
            FROM coupon c
            INNER JOIN coupon_stock cs
                ON cs.coupon_id = c.coupon_id
            WHERE c.coupon_id = :couponId
            """;

    private final JdbcClient jdbcClient;

    @Override
    public Optional<CouponRedisInitializationData> findByCouponId(
            long couponId
    ) {
        return jdbcClient.sql(FIND_BY_COUPON_ID)
                .param("couponId", couponId)
                .query((resultSet, rowNumber) ->
                        new CouponRedisInitializationData(
                                resultSet.getLong("coupon_id"),
                                resultSet.getInt("remaining_quantity"),
                                resultSet.getTimestamp("open_at")
                                        .toLocalDateTime(),
                                resultSet.getTimestamp("close_at")
                                        .toLocalDateTime()))
                .optional();
    }
}