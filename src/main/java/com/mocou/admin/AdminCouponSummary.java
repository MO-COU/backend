package com.mocou.admin;

import java.time.LocalDateTime;

/**
 * 관리자 대시보드 목록에 쓰는 회차 한 줄.
 *
 * <p>재고는 총량만 담는다. 잔여 수량은 Redis를 봐야 하는데 목록에서 회차 수만큼 Redis를 조회하면 화면 하나에 왕복이 회차 수만큼
 * 생긴다. 실시간 수치는 회차를 고른 뒤 상세 화면의 {@link AdminCouponStock}이 맡는다.
 */
public record AdminCouponSummary(
        long couponId,
        String name,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        int totalQuantity,
        String status) {}
