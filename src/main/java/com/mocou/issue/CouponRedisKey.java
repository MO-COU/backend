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
     * Redis Lua 예약 성공 순번을 생성하는 Counter Key.
     */
    public static String issueSequence(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-sequence".formatted(couponId);
    }

    /**
     * 발급 동기화 재시도 한도를 넘긴 이벤트를 보관하는 DLQ Stream Key.
     */
    public static String issueDlqStream(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-dlq".formatted(couponId);
    }

    /**
     * DLQ 복구마저 재시도 한도를 넘겨 최종 실패로 확정된 이벤트를 보관하는 Stream Key.
     * 이 스트림은 관리자가 확인할 때까지 삭제되지 않는다 - 관리자 조회 API의 조회 대상이다.
     */
    public static String issueDlqFailedStream(long couponId) {
        validateCouponId(couponId);
        return "coupon:{%d}:issue-dlq-failed".formatted(couponId);
    }

    private static void validateCouponId(long couponId) {
        if (couponId <= 0) {
            throw new IllegalArgumentException("couponId는 양수여야 합니다.");
        }
    }
}
