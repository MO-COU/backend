package com.mocou.lifecycle;

import java.time.LocalDateTime;
import java.util.List;

public interface CouponExpirationRepository {

    LocalDateTime currentDatabaseTime();

    List<CouponExpirationCandidate> findDueIssues(LocalDateTime cutoffAt, int limit);

    int[] markExpiredBatch(List<CouponExpirationCandidate> candidates, LocalDateTime cutoffAt);

    void saveExpiredHistories(List<CouponExpirationCandidate> candidates);
}
