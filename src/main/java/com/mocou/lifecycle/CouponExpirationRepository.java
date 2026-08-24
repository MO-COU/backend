package com.mocou.lifecycle;

import java.time.LocalDateTime;
import java.util.List;

/** 만료 후보 조회와 상태·이력 저장을 담당하는 저장소 경계다. */
public interface CouponExpirationRepository {

    List<CouponExpirationCandidate> findDueIssues(LocalDateTime cutoffAt, int limit);

    List<CouponExpirationCandidate> findDueIssues(LocalDateTime cutoffAt, int limit, long couponId);

    int[] markExpiredBatch(List<CouponExpirationCandidate> candidates, LocalDateTime cutoffAt);

    void saveExpiredHistories(List<CouponExpirationCandidate> candidates);
}
