package com.mocou.admin;

import java.util.List;
import java.util.Optional;

public interface AdminCouponRepository {

    boolean existsCoupon(long couponId);

    /** 대시보드 목록. 최근 회차가 먼저 온다. */
    List<AdminCouponSummary> findAllSummaries();

    Optional<AdminCouponStock> findStock(long couponId);

    long countIssues(long couponId);

    List<AdminCouponIssue> findIssues(long couponId, int size, long offset);

    /** DLQ 최종 실패(INTERNAL_ERROR)로 기록된 issue_failure_log 항목. 회원당 최신 1건만 담는다. */
    List<AdminCouponFailureLogEntry> findDlqFailureLogs(long couponId);
}
