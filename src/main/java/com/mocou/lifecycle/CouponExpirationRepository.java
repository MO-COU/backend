package com.mocou.lifecycle;

import java.time.LocalDateTime;
import java.util.List;

public interface CouponExpirationRepository {

    LocalDateTime currentDatabaseTime();

    List<CouponExpirationCandidate> findDueIssues(LocalDateTime cutoffAt, int limit);

    int markExpired(long issueId, LocalDateTime cutoffAt);

    void saveExpiredHistory(CouponExpirationCandidate candidate);
}
