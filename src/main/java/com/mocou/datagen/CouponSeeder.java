package com.mocou.datagen;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 메타데이터(카탈로그)를 생성한다.
 *
 * <p>과거 이력을 담을 더미 쿠폰과 A팀 부하 테스트용 시연 쿠폰을 분리한다. 시연 쿠폰에는 발급 이력을 만들지 않으므로, 부하 테스트를 몇 번 반복해도
 * 정합성 검증 대상 데이터가 변하지 않는다.
 */
@Component
@RequiredArgsConstructor
class CouponSeeder {

    private static final String DEMO_COUPON_NAME = "선착순 시연 쿠폰";
    private static final String INSERT_COUPON_SQL =
            "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_STOCK_SQL =
            "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final DatagenProperties properties;

    /** 기준 시각으로부터 쿠폰 규격을 계산한다. DB에 접근하지 않으므로 규격만 따로 검증할 수 있다. */
    List<CouponSeedSpec> specs(LocalDateTime baseTime) {
        List<CouponSeedSpec> specs = new ArrayList<>();
        for (int index = 1; index <= properties.dummyCouponCount(); index++) {
            specs.add(
                    new CouponSeedSpec(
                            index,
                            "더미 캠페인 " + index,
                            baseTime.minusDays(90),
                            baseTime,
                            "CLOSED",
                            properties.dummyCouponTotalQuantity()));
        }
        specs.add(
                new CouponSeedSpec(
                        demoCouponId(),
                        DEMO_COUPON_NAME,
                        baseTime.minusDays(1),
                        baseTime.plusDays(365),
                        "OPEN",
                        properties.demoCouponTotalQuantity()));
        return specs;
    }

    long demoCouponId() {
        return properties.dummyCouponCount() + 1L;
    }

    @Transactional
    List<CouponSeedSpec> seed(LocalDateTime baseTime) {
        List<CouponSeedSpec> specs = specs(baseTime);
        for (CouponSeedSpec spec : specs) {
            jdbcTemplate.update(
                    INSERT_COUPON_SQL,
                    spec.couponId(),
                    spec.name(),
                    Timestamp.valueOf(spec.openAt()),
                    Timestamp.valueOf(spec.closeAt()),
                    spec.status(),
                    Timestamp.valueOf(baseTime));
            jdbcTemplate.update(
                    INSERT_STOCK_SQL, spec.couponId(), spec.totalQuantity(), spec.totalQuantity());
        }
        return specs;
    }
}
