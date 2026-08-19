package com.mocou.issue;

public final class CouponRedisKey {
    private CouponRedisKey() {
    }

    public static String stock(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:stock".formatted(couponId);
    }

    public static String issuedMembers(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issued-members".formatted(couponId);
    }

    /**
     * 쿠폰 발급 가능 시간 정보를 저장하는 Redis Hash Key.
     */
    public static String metadata(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:metadata".formatted(couponId);
    }

    private static void validateCouponId(long couponId) {
        if (couponId <= 0) {
            throw new IllegalArgumentException("couponId는 양수여야 합니다.");
        }
    }
}
