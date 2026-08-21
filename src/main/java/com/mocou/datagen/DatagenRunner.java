package com.mocou.datagen;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 더미데이터 생성 진입점.
 *
 * <p>{@code datagen} 프로필로 기동할 때만 동작한다. HTTP로 열지 않는 이유는, 요청 한 번이 수백만 행 적재를 유발하고 중복 실행 시
 * {@code UNIQUE (coupon_id, member_id)}에 걸려 데이터가 절반만 들어간 상태로 남기 때문이다.
 *
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local,datagen --mocou.datagen.member-count=10000'
 * </pre>
 */
@Slf4j
@Component
@Profile("datagen")
@RequiredArgsConstructor
class DatagenRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DatagenProperties properties;
    private final CouponSeeder couponSeeder;
    private final MemberGenerator memberGenerator;
    private final IssueGenerator issueGenerator;
    private final StockReconciler stockReconciler;

    @Override
    public void run(ApplicationArguments args) {
        if (isAlreadySeeded()) {
            log.warn("이미 데이터가 있어 생성을 건너뛴다. 다시 만들려면 대상 테이블을 비운 뒤 실행한다.");
            return;
        }

        LocalDateTime baseTime = resolveBaseTime();
        log.info("더미데이터 생성 시작 (기준 시각 {}, 시드 {})", baseTime, properties.seed());

        List<CouponSeedSpec> rounds = couponSeeder.seed(baseTime);
        // 회원은 첫 회차보다 먼저 가입해 있어야 한다. 그래야 가입 전에 쿠폰을 받은 이력이 생기지 않는다.
        int members = memberGenerator.generate(rounds.get(0).openAt());
        int issues = issueGenerator.generate(rounds, baseTime, couponSeeder.demoCouponId());
        // 재고는 미리 정하지 않고 실제로 들어간 발급 건을 세어 역산한다.
        stockReconciler.reconcile();

        log.info("더미데이터 생성 완료 (회차 {}개, 회원 {}건, 발급 {}건)", rounds.size(), members, issues);
    }

    private boolean isAlreadySeeded() {
        Long members = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member", Long.class);
        Long coupons = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon", Long.class);
        return (members != null && members > 0) || (coupons != null && coupons > 0);
    }

    /** 기준 시각을 지정하지 않으면 DB 시각을 쓴다. 서버와 DB의 시각이 어긋나도 데이터 안에서는 한 기준만 쓰이게 한다. */
    private LocalDateTime resolveBaseTime() {
        if (properties.baseTime() != null) {
            return properties.baseTime();
        }
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime());
    }
}
