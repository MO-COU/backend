package com.mocou.lifecycle;

import java.util.Optional;

public interface CouponUseRepository {

    Optional<CouponIssueStatus> findHistoryTargetStatus(long issueId, String idempotencyKey);

    int markUsed(long issueId);

    void saveUsedHistory(long issueId, String idempotencyKey);

    Optional<CouponIssueState> findIssue(long issueId);
}
