package com.mocou.issue.initialization;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponRedisInitializationService {

    /*
     * MySQL DATETIME에는 Zone 정보가 없으므로 프로젝트의 DB 시각 기준을
     * 명시적으로 적용.
     */
    private static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final CouponRedisInitializationRepository repository;
    private final RedisCouponInitializationGateway redisGateway;

    public CouponRedisInitializationResult initialize(
            long couponId
    ) {
        validateCouponId(couponId);

        CouponRedisInitializationData data =
                repository.findByCouponId(couponId)
                        .orElseThrow(() ->
                                new CouponRedisInitializationDataNotFoundException(
                                        couponId));

        return redisGateway.initialize(
                data.couponId(),
                data.remainingQuantity(),
                toEpochSecond(data.openAt()),
                toEpochSecond(data.closeAt()));
    }

    private long toEpochSecond(LocalDateTime dateTime) {
        return dateTime
                .atZone(COUPON_TIME_ZONE)
                .toEpochSecond();
    }

    private void validateCouponId(long couponId) {
        if (couponId <= 0) {
            throw new IllegalArgumentException("couponId는 양수여야 합니다.");
        }
    }
}