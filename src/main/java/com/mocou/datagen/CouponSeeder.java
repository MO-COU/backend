package com.mocou.datagen;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 카탈로그를 회차 단위로 생성한다.
 *
 * <p>매주 월요일 10시에 같은 쿠폰을 다시 여는 서비스를 가정한다. {@code coupon} 행 하나가 회차 하나이며, 회차 번호가 클수록 최근이다.
 * {@code UNIQUE (coupon_id, member_id)}가 회차당 1인 1매를 그대로 보증한다.
 *
 * <p>마지막 하나는 A팀 부하 테스트용이라 요일 격자에서 빼고 이미 열린 상태로 만든다. 진짜 다음 월요일을 오픈 시각으로 두면 월요일이 아닌 날
 * 부하 테스트를 돌릴 때 오픈 전이라는 이유로 전건 거부된다. 이 회차에는 발급 이력을 만들지 않아, 부하 테스트를 반복해도 정합성 검증 대상
 * 데이터가 변하지 않는다.
 */
@Component
@RequiredArgsConstructor
class CouponSeeder {

    private static final String PRODUCT_NAME = "아메리카노 무료 쿠폰";
    private static final int OPEN_HOUR = 10;

    private static final String INSERT_COUPON_SQL =
            "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_STOCK_SQL =
            "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final DatagenProperties properties;

    /** 기준 시각으로부터 회차 규격을 계산한다. DB에 접근하지 않으므로 격자만 따로 검증할 수 있다. */
    List<CouponSeedSpec> specs(LocalDateTime baseTime) {
        LocalDateTime latestOpenAt = latestOpenedMonday(baseTime);
        int roundCount = properties.roundCount();

        List<CouponSeedSpec> specs = new ArrayList<>(roundCount + 1);
        for (int round = 1; round <= roundCount; round++) {
            LocalDateTime openAt = latestOpenAt.minusWeeks(roundCount - round);
            specs.add(
                    new CouponSeedSpec(
                            round,
                            roundName(round),
                            openAt,
                            endOfDay(openAt),
                            "CLOSED",
                            properties.roundStock()));
        }
        specs.add(
                new CouponSeedSpec(
                        demoCouponId(),
                        roundName(roundCount + 1),
                        baseTime.minusDays(1),
                        baseTime.plusDays(365),
                        "OPEN",
                        properties.demoCouponTotalQuantity()));
        return specs;
    }

    /** 부하 테스트용 회차. 유일하게 {@code OPEN} 상태이며 발급 이력을 갖지 않는다. */
    long demoCouponId() {
        return properties.roundCount() + 1L;
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

    /**
     * 기준 시각 이전에 이미 열린 가장 최근 월요일 10시.
     *
     * <p>기준 시각이 월요일 09시라면 가장 가까운 월요일은 오늘이지만 오픈 시각이 아직 지나지 않았다. 열리지도 않은 회차에 발급 이력을
     * 만들면 데이터가 모순되므로 한 주 앞으로 물린다.
     */
    private LocalDateTime latestOpenedMonday(LocalDateTime baseTime) {
        LocalDateTime monday =
                baseTime
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .withHour(OPEN_HOUR)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        return monday.isAfter(baseTime) ? monday.minusWeeks(1) : monday;
    }

    private String roundName(int round) {
        return PRODUCT_NAME + " " + round + "회차";
    }

    /** 하루짜리 이벤트다. 선착순이라 대개 오픈 직후 끝나지만 형식상 그날 자정까지 열어둔다. */
    private LocalDateTime endOfDay(LocalDateTime openAt) {
        return openAt.toLocalDate().atTime(23, 59, 59);
    }
}
