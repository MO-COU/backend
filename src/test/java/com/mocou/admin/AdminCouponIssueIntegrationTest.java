package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class AdminCouponIssueIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;

    @Autowired private AdminCouponService service;

    @Test
    @DisplayName("MySQL에 적재된 쿠폰 발급 이력을 최신순으로 조회한다")
    void readsCouponIssuesFromMySql() {
        // given
        insertCouponAndMembers();
        insertIssue(3001L, 1001L, "2026-08-19 10:00:00");
        insertIssue(3002L, 1002L, "2026-08-19 10:01:00");

        // when
        AdminCouponIssuePage result = service.getIssues(COUPON_ID, 0, 1);

        // then
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.content()).extracting(AdminCouponIssue::issueId).containsExactly(3002L);
    }

    @Test
    @DisplayName("MySQL에 반영된 쿠폰 재고를 조회한다")
    void readsCouponStockFromMySql() {
        // given
        insertCouponAndMembers();
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) "
                        + "VALUES (?, 10000, 2000)",
                COUPON_ID);

        // when
        AdminCouponStock result = service.getStock(COUPON_ID);

        // then
        assertThat(result.totalQuantity()).isEqualTo(10_000);
        assertThat(result.issuedQuantity()).isEqualTo(8_000);
        assertThat(result.remainingQuantity()).isEqualTo(2_000);
        assertThat(result.updatedAt()).isNotNull();
    }

    private void insertCouponAndMembers() {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES "
                        + "(1001, 'member1@example.com', '회원1', '01000000001'), "
                        + "(1002, 'member2@example.com', '회원2', '01000000002')");
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, discount_rate, open_at, close_at, status) "
                        + "VALUES (?, '관리자 조회 테스트 쿠폰', 10, "
                        + "CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY, 'OPEN')",
                COUPON_ID);
    }

    private void insertIssue(long issueId, long memberId, String issuedAt) {
        LocalDateTime issuedDateTime = LocalDateTime.parse(issuedAt.replace(' ', 'T'));
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(coupon_issue_id, coupon_id, member_id, status, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, 'ISSUED', ?, ?)",
                issueId,
                COUPON_ID,
                memberId,
                issuedDateTime,
                issuedDateTime.plusDays(7));
    }
}
