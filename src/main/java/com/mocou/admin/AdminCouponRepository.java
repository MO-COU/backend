package com.mocou.admin;

import java.util.List;
import java.util.Optional;

public interface AdminCouponRepository {

    boolean existsCoupon(long couponId);

    Optional<AdminCouponStock> findStock(long couponId);

    long countIssues(long couponId);

    List<AdminCouponIssue> findIssues(long couponId, int size, long offset);
}
