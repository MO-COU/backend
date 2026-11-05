package com.mocou.consistency;

import java.util.Map;

/**
 * 검증 스냅샷을 열기 직전의 Redis 동기화 상태.
 *
 * <p>Redis는 DB 스냅샷 밖에 있다. Redis와 DB를 대조하려면 스냅샷을 열기 전에 동기화가 끝나 있었고, 대조하는 동안 발급이
 * 없었다는 것을 따로 보장해야 한다. 그 기준값을 스냅샷 직전에 남긴다.
 *
 * @param captured 기록했는지
 * @param couponStates 기록 시점에 발급을 열고 있던 회차별 상태
 * @param captureFailure 기록하지 못한 사유. 기록했으면 {@code null}
 */
public record RedisSyncBaseline(
        boolean captured, Map<Long, CouponState> couponStates, String captureFailure) {

    /**
     * @param pendingReason 동기화가 끝나지 않았으면 그 사유, 끝났으면 {@code null}
     * @param issueSequence Redis 발급 순번 원문. 키가 없으면 {@code null}
     */
    public record CouponState(String pendingReason, String issueSequence) {}

    public RedisSyncBaseline {
        couponStates = Map.copyOf(couponStates);
    }

    public static RedisSyncBaseline notCaptured() {
        return new RedisSyncBaseline(false, Map.of(), null);
    }

    public static RedisSyncBaseline of(Map<Long, CouponState> couponStates) {
        return new RedisSyncBaseline(true, couponStates, null);
    }

    public static RedisSyncBaseline failed(String reason) {
        return new RedisSyncBaseline(true, Map.of(), reason);
    }

    /** 이 회차를 대조할 수 없는 사유. 대조할 수 있으면 {@code null}. */
    public String notReadyReason(long couponId) {
        if (!captured) {
            return "검증 시작 전 Redis 동기화 상태를 기록하지 않아 Redis와 DB를 대조할 수 없다";
        }
        if (captureFailure != null) {
            return "검증 시작 전 Redis 동기화 상태를 읽지 못했다: " + captureFailure;
        }
        CouponState state = couponStates.get(couponId);
        if (state == null) {
            return "쿠폰 %d는 검증 시작 뒤에 열린 회차다. 다시 검증해야 한다".formatted(couponId);
        }
        return state.pendingReason();
    }

    public String issueSequence(long couponId) {
        CouponState state = couponStates.get(couponId);
        return state == null ? null : state.issueSequence();
    }
}
