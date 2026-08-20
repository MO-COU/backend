package com.mocou.issue.initialization;

import java.time.LocalDateTime;
import java.util.Objects;

public record CouponRedisInitializationData(
        long couponId,
        int remainingQuantity,
        LocalDateTime openAt,
        LocalDateTime closeAt
) {
    public CouponRedisInitializationData {
        if (couponId <= 0) {
            throw new IllegalArgumentException("couponId는 양수여야 합니다.");
        }

        if (remainingQuantity < 0) {
            throw new IllegalArgumentException("잔여 재고는 음수일 수 없습니다.");
        }

        Objects.requireNonNull(openAt, "openAt은 필수입니다.");
        Objects.requireNonNull(closeAt, "closeAt은 필수입니다.");

        if (!openAt.isBefore(closeAt)) {
            throw new IllegalArgumentException("openAt은 closeAt보다 이전이어야 합니다.");
        }
    }
}
