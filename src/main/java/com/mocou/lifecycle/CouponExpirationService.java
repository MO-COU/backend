package com.mocou.lifecycle;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponExpirationService {

    private final CouponExpirationRepository repository;

    public CouponExpirationService(CouponExpirationRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireDueIssues(LocalDateTime cutoffAt, int chunkSize) {
        List<CouponExpirationCandidate> candidates = repository.findDueIssues(cutoffAt, chunkSize);
        for (CouponExpirationCandidate candidate : candidates) {
            if (repository.markExpired(candidate.couponIssueId(), cutoffAt) == 1) {
                repository.saveExpiredHistory(candidate);
            }
        }
        return candidates.size();
    }
}
