package com.mocou.admin;

import java.util.OptionalInt;

/** 관리자 화면에 표시할 Redis 실시간 잔여 재고를 읽는다. */
public interface AdminCouponRealtimeStockRepository {

    /** Redis가 아직 초기화되지 않은 쿠폰이면 빈 값을 반환한다. */
    OptionalInt findRemainingQuantity(long couponId);
}
