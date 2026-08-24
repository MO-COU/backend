package com.mocou.datagen;

import java.time.LocalDateTime;

/**
 * 생성할 쿠폰 한 종의 규격.
 *
 * <p>{@code totalQuantity}는 재고 마스터에 그대로 기록되고, {@code remaining_quantity}는 발급 이력을 적재한 뒤 쿠폰별 발급
 * 건수를 집계해 역산한다. 재고를 먼저 확정하면 STOCK_MISMATCH 규칙이 최초 검증부터 위반을 검출한다.
 */
record CouponSeedSpec(
        long couponId,
        String name,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        String status,
        int totalQuantity) {}
