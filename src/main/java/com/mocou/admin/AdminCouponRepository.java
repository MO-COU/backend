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
}
