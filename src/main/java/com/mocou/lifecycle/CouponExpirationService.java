package com.mocou.lifecycle;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
/** 조건부 만료 갱신에 성공한 발급 건에만 만료 이력을 함께 저장한다. */
public class CouponExpirationService {

    private final CouponExpirationRepository repository;

    public CouponExpirationService(CouponExpirationRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireDueIssues(LocalDateTime cutoffAt, int chunkSize) {
        List<CouponExpirationCandidate> candidates = repository.findDueIssues(cutoffAt, chunkSize);
        return expireCandidates(candidates, cutoffAt);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireDueIssues(LocalDateTime cutoffAt, int chunkSize, long couponId) {
        List<CouponExpirationCandidate> candidates =
                repository.findDueIssues(cutoffAt, chunkSize, couponId);
        return expireCandidates(candidates, cutoffAt);
    }

    private int expireCandidates(List<CouponExpirationCandidate> candidates, LocalDateTime cutoffAt) {
        int[] updateCounts = repository.markExpiredBatch(candidates, cutoffAt);
        List<CouponExpirationCandidate> expiredCandidates = new ArrayList<>();

        for (int index = 0; index < candidates.size(); index++) {
            if (updateCounts[index] > 0 || updateCounts[index] == Statement.SUCCESS_NO_INFO) {
                expiredCandidates.add(candidates.get(index));
            }
        }

        if (!expiredCandidates.isEmpty()) {
            repository.saveExpiredHistories(expiredCandidates);
        }

        return candidates.size();
    }
}
