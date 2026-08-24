package com.mocou.issue.initialization;

public class CouponRedisInitializationDataNotFoundException
        extends RuntimeException {

    public CouponRedisInitializationDataNotFoundException(long couponId) {
        super("Redis 초기화에 필요한 쿠폰 또는 재고가 없습니다. " + "couponId=" + couponId);
    }
}