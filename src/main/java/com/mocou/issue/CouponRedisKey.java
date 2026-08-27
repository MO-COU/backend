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

    /**
     * 쿠폰 발급 예약 이벤트를 저장하는 Redis Stream Key.
     */
    public static String issueStream(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-stream".formatted(couponId);
    }

    /**
     * Redis 예약 단계의 결과별 요청 수를 저장하는 Hash Key.
     */
    public static String issueResultCounts(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-result-counts".formatted(couponId);
    }

    /**
     * 발급 동기화 재시도 한도를 넘긴 이벤트를 보관하는 DLQ Stream Key.
     * 원본 {@link #issueStream(long)}과 같은 필드 구조를 그대로 옮겨 담는다.
     */
    public static String issueDlqStream(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-dlq".formatted(couponId);
    }

    private static void validateCouponId(long couponId) {
        if (couponId <= 0) {
            throw new IllegalArgumentException("couponId는 양수여야 합니다.");
        }
    }
}
