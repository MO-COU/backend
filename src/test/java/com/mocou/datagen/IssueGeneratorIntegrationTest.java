package com.mocou.datagen;

import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 수와 재고를 줄여 구조만 확인한다. 규칙이 비율과 계산으로 되어 있어 규모를 줄여도 그대로 성립한다.
 *
 * <p>회차 4개 × 재고 250 = 발급 1,000건. 마지막 두 회차는 유효기간이 남아 ISSUED가 섞인다.
 */
@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            "mocou.datagen.member-count=2000",
            "mocou.datagen.round-count=4",
            "mocou.datagen.round-stock=250",
            "mocou.datagen.chunk-size=500"
        })
class IssueGeneratorIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 19, 12, 0);

    @Autowired private CouponSeeder couponSeeder;
    @Autowired private MemberGenerator memberGenerator;
    @Autowired private IssueGenerator issueGenerator;
    @Autowired private StockReconciler stockReconciler;
    @Autowired private DatagenProperties properties;

    private List<CouponSeedSpec> rounds;

    @BeforeEach
    void seedCouponsAndMembers() {
        rounds = couponSeeder.seed(BASE_TIME);
        memberGenerator.generate(rounds.get(0).openAt());
    }

    @Test
    @DisplayName("회차 수와 재고를 곱한 만큼 발급 이력이 만들어진다")
    void createsIssuesForEveryRoundUpToStock() {
        // when
        int issued = issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then
        int expected = properties.roundCount() * properties.roundStock();
        assertThat(issued).isEqualTo(expected);
        assertThat(count("coupon_issue")).isEqualTo(expected);
    }

    @Test
    @DisplayName("시연 회차에는 발급 이력을 만들지 않는다")
    void leavesDemoRoundEmpty() {
        // when
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?",
                                Long.class,
                                couponSeeder.demoCouponId()))
                .isZero();
    }

    /** 발급만 들어가고 이력이 빠지면 그 자체로 HISTORY_MISSING 위반 데이터가 된다. */
    @Test
    @DisplayName("모든 발급 건이 최초 이력을 갖고, 최종 상태 건은 후속 이력을 하나 더 갖는다")
    void everyIssueHasItsHistory() {
        // when
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then - 최초 이력이 없는 발급 건
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_issue i WHERE NOT EXISTS ("
                                        + "SELECT 1 FROM coupon_issue_history h "
                                        + "WHERE h.coupon_issue_id = i.coupon_issue_id "
                                        + "AND h.from_status IS NULL AND h.to_status = 'ISSUED')",
                                Long.class))
                .isZero();

        // 최종 상태인데 후속 이력이 없는 발급 건
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_issue i WHERE i.status <> 'ISSUED' "
                                        + "AND NOT EXISTS ("
                                        + "SELECT 1 FROM coupon_issue_history h "
                                        + "WHERE h.coupon_issue_id = i.coupon_issue_id "
                                        + "AND h.from_status = 'ISSUED' AND h.to_status = i.status)",
                                Long.class))
                .isZero();
    }

    /** 정합성 검증의 STATE_TIMESTAMP_MISMATCH 규칙이 검사할 항목을 적재 결과에서 그대로 확인한다. */
    @Test
    @DisplayName("적재된 행에 상태와 시각의 모순이 없다")
    void loadedRowsHaveNoStateTimestampContradiction() {
        // when
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then
        assertThat(countIssuesWhere("status = 'USED' AND used_at IS NULL")).isZero();
        assertThat(countIssuesWhere("status = 'USED' AND (used_at < issued_at OR used_at >= expires_at)"))
                .isZero();
        assertThat(countIssuesWhere("status <> 'USED' AND used_at IS NOT NULL")).isZero();
        assertThat(countIssuesWhere("expires_at <= issued_at")).isZero();
    }

    @Test
    @DisplayName("회차 안에서 같은 회원이 두 번 발급받지 않는다")
    void neverIssuesTwiceToTheSameMemberInARound() {
        // when
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT coupon_id, member_id FROM coupon_issue "
                                        + "GROUP BY coupon_id, member_id HAVING COUNT(*) >= 2) v",
                                Long.class))
                .isZero();
    }

    /** 정합성 검증의 STOCK_MISMATCH 규칙이 보는 식이다. */
    @Test
    @DisplayName("역산 후 모든 회차에서 총 재고 = 발급 건수 + 잔여 재고가 성립한다")
    void reconciledStockMatchesIssuedCount() {
        // given
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // when
        stockReconciler.reconcile();

        // then
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_stock s WHERE s.total_quantity <> "
                                        + "s.remaining_quantity + ("
                                        + "SELECT COUNT(*) FROM coupon_issue i WHERE i.coupon_id = s.coupon_id)",
                                Long.class))
                .isZero();
    }

    @Test
    @DisplayName("발급 이력이 없는 시연 회차는 재고가 그대로 남는다")
    void leavesDemoRoundStockUntouched() {
        // given
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // when
        stockReconciler.reconcile();

        // then
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT remaining_quantity FROM coupon_stock WHERE coupon_id = ?",
                                Integer.class,
                                couponSeeder.demoCouponId()))
                .isEqualTo(properties.demoCouponTotalQuantity());
    }

    @Test
    @DisplayName("같은 기준 시각으로 다시 만들면 발급과 이력이 완전히 같다")
    void regeneratingProducesIdenticalRows() {
        // given
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());
        String first = fingerprint();

        // when
        jdbcTemplate.update("DELETE FROM coupon_issue_history");
        jdbcTemplate.update("DELETE FROM coupon_issue");
        issueGenerator.generate(rounds, BASE_TIME, couponSeeder.demoCouponId());

        // then
        assertThat(fingerprint()).isEqualTo(first);
    }

    private String fingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT CONCAT("
                        + "(SELECT CONCAT(COUNT(*), ':', SUM(CRC32(CONCAT_WS('|', coupon_issue_id, "
                        + "coupon_id, member_id, status, issued_at, IFNULL(used_at, ''), expires_at)))) "
                        + "FROM coupon_issue), '/', "
                        + "(SELECT CONCAT(COUNT(*), ':', SUM(CRC32(CONCAT_WS('|', coupon_issue_id, "
                        + "IFNULL(from_status, ''), to_status, changed_at, idempotency_key)))) "
                        + "FROM coupon_issue_history))",
                String.class);
    }

    private long countIssuesWhere(String condition) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue WHERE " + condition, Long.class);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
