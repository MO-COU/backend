package com.mocou.issue.initialization;

import java.util.Optional;

public interface CouponRedisInitializationRepository {
    Optional<CouponRedisInitializationData> findByCouponId(long couponId);
}
