package com.mocou.datagen;

import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            "mocou.datagen.member-count=1000",
            "mocou.datagen.chunk-size=250"
        })
class DatagenIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 19, 0, 0);

    @Autowired private CouponSeeder couponSeeder;
    @Autowired private MemberGenerator memberGenerator;
    @Autowired private DatagenProperties properties;

    @Test
    @DisplayName("더미 쿠폰과 시연 쿠폰을 재고와 함께 생성한다")
    void seedsDummyCouponsAndOneDemoCoupon() {
        // given
        int expectedCount = properties.dummyCouponCount() + 1;

        // when
        List<CouponSeedSpec> specs = couponSeeder.seed(BASE_TIME);

        // then
        assertThat(specs).hasSize(expectedCount);
        assertThat(couponCount()).isEqualTo(expectedCount);

        Map<String, Object> demo =
                jdbcTemplate.queryForMap(
                        "SELECT c.status, s.total_quantity, s.remaining_quantity "
                                + "FROM coupon c JOIN coupon_stock s ON s.coupon_id = c.coupon_id "
                                + "WHERE c.coupon_id = ?",
                        couponSeeder.demoCouponId());
        assertThat(demo.get("status")).isEqualTo("OPEN");
        assertThat(demo.get("total_quantity")).isEqualTo(properties.demoCouponTotalQuantity());
        assertThat(demo.get("remaining_quantity")).isEqualTo(properties.demoCouponTotalQuantity());
    }

    @Test
    @DisplayName("시연 쿠폰은 이미 열려 있어 부하 테스트가 곧바로 발급할 수 있다")
    void demoCouponIsOpenSoLoadTestCanIssueImmediately() {
        // given
        long demoCouponId = couponSeeder.demoCouponId();

        // when
        couponSeeder.seed(BASE_TIME);

        // then
        assertThat(couponTime("open_at", demoCouponId)).isBefore(BASE_TIME);
        assertThat(couponTime("close_at", demoCouponId)).isAfter(BASE_TIME);
    }

    @Test
    @DisplayName("회원을 설정한 수만큼 1번부터 연속된 번호로 적재한다")
    void generatesRequestedNumberOfMembers() {
        // given
        long expectedCount = properties.memberCount();

        // when
        memberGenerator.generate(BASE_TIME);

        // then
        assertThat(memberCount()).isEqualTo(expectedCount);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM member WHERE member_id BETWEEN 1 AND ?",
                                Long.class,
                                expectedCount))
                .isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("마스킹 대상 필드가 정해진 형식을 벗어나지 않는다")
    void maskableFieldsFollowFixedFormat() {
        // given, when
        memberGenerator.generate(BASE_TIME);

        // then
        assertThat(countMembersWhere("phone NOT REGEXP '^010-[0-9]{4}-[0-9]{4}$'")).isZero();
        assertThat(countMembersWhere("email NOT LIKE '%@mocou.test'")).isZero();
        assertThat(countMembersWhere("CHAR_LENGTH(name) <> 3")).isZero();
    }

    @Test
    @DisplayName("같은 기준 시각으로 다시 생성하면 회원 데이터가 완전히 같다")
    void regeneratingWithSameBaseTimeProducesIdenticalMembers() {
        // given
        memberGenerator.generate(BASE_TIME);
        String firstRun = memberFingerprint();

        // when
        jdbcTemplate.update("DELETE FROM member");
        memberGenerator.generate(BASE_TIME);

        // then
        assertThat(memberFingerprint()).isEqualTo(firstRun);
    }

    /**
     * 청크 단위 트랜잭션의 경계를 확인한다.
     *
     * <p>회원 1,000명을 250건씩 나누므로 청크는 4개다. 3번째 청크(501~750) 한가운데에서 실패시켰을 때, 앞선 두 청크는 남고 실패한
     * 청크는 통째로 사라져야 한다. 트랜잭션을 걸지 않으면 실패 직전까지 들어간 행이 그대로 남아 이 검증이 깨진다.
     */
    @Test
    @DisplayName("청크 하나가 실패하면 그 청크만 롤백되고 앞선 청크는 남는다")
    void rollsBackOnlyFailedChunk() {
        // given - 세 번째 청크 한가운데에 같은 번호의 회원을 미리 넣어 충돌을 만든다
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                600L,
                "conflict@mocou.test",
                "김충돌",
                "010-0000-0000",
                Timestamp.valueOf(BASE_TIME));

        // when
        assertThatThrownBy(() -> memberGenerator.generate(BASE_TIME))
                .isInstanceOf(DataAccessException.class);

        // then
        assertThat(countMembersWhere("member_id BETWEEN 1 AND 500")).isEqualTo(500);
        assertThat(countMembersWhere("member_id BETWEEN 501 AND 750 AND member_id <> 600")).isZero();
        assertThat(countMembersWhere("member_id BETWEEN 751 AND 1000")).isZero();
    }

    /** GROUP_CONCAT은 기본 길이 제한에 걸려 일부 행만 반영되므로, 행 수와 행별 체크섬 합계를 함께 쓴다. */
    private String memberFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT CONCAT(COUNT(*), ':', "
                        + "SUM(CRC32(CONCAT_WS('|', member_id, email, name, phone, created_at)))) "
                        + "FROM member",
                String.class);
    }

    private LocalDateTime couponTime(String column, long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM coupon WHERE coupon_id = ?", LocalDateTime.class, couponId);
    }

    private long countMembersWhere(String condition) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE " + condition, Long.class);
    }

    private long couponCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon", Long.class);
    }

    private long memberCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member", Long.class);
    }
}
