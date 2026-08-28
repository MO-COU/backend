package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class AdminCouponIssueIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;

    @Autowired private AdminCouponService service;
    @MockitoBean private AdminCouponRealtimeStockRepository realtimeStockRepository;
    @MockitoBean private RedisAdminCouponIssueResultRepository issueResultRepository;

    @Test
    @DisplayName("MySQL 발급 이력 수를 Redis 발급 결과의 DB 적재 진행에 반영한다")
    void includesPersistedIssueCountInIssueResultCounts() {
        // given
        insertCouponAndMembers();
        insertIssue(3001L, 1001L, "2026-08-19 10:00:00");
        insertIssue(3002L, 1002L, "2026-08-19 10:01:00");
        given(issueResultRepository.findCounts(COUPON_ID))
                .willReturn(AdminCouponIssueResultCounts.of(COUPON_ID, 5, 0, 0, 0, 0, 0, 0, 1));

        // when
        AdminCouponIssueResultCounts result = service.getIssueResultCounts(COUPON_ID);

        // then
        assertThat(result.dbPersisted()).isEqualTo(2);
        assertThat(result.pendingOrRetrying()).isEqualTo(2);
    }

    @Test
    @DisplayName("발급 이력을 선착순으로 조회하고 순번이 없는 이력은 마지막에 표시한다")
    void readsCouponIssuesFromMySql() {
        // given
        insertCouponAndMembers();
        insertIssue(3001L, 1001L, "2026-08-19 10:00:00", 2L, 9_998L);
        insertIssue(3002L, 1002L, "2026-08-19 10:01:00", 1L, 9_999L);
        insertIssue(3003L, 1003L, "2026-08-19 10:02:00");

        // when
        AdminCouponIssuePage firstPage = service.getIssues(COUPON_ID, 0, 2);
        AdminCouponIssuePage secondPage = service.getIssues(COUPON_ID, 1, 2);

        // then
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.content())
                .extracting(AdminCouponIssue::issueId)
                .containsExactly(3002L, 3001L);
        assertThat(firstPage.content())
                .extracting(AdminCouponIssue::issueSequence)
                .containsExactly(1L, 2L);
        assertThat(firstPage.content().getFirst().memberName()).isEqualTo("회*2");
        assertThat(firstPage.content().getFirst().memberEmail())
                .isEqualTo("me*****@example.com");
        assertThat(firstPage.content().getFirst().memberPhone()).isEqualTo("010-****-0002");

        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.content()).extracting(AdminCouponIssue::issueId).containsExactly(3003L);
        assertThat(secondPage.content().getFirst().issueSequence()).isNull();
        assertThat(secondPage.content().getFirst().remainingAtIssue()).isNull();
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
        assertThat(result.couponName()).isEqualTo("관리자 조회 테스트 쿠폰");
        assertThat(result.openAt()).isNotNull();
        assertThat(result.totalQuantity()).isEqualTo(10_000);
        assertThat(result.issuedQuantity()).isEqualTo(8_000);
        assertThat(result.dbIssuedQuantity()).isZero();
        assertThat(result.syncGapQuantity()).isEqualTo(8_000);
        assertThat(result.remainingQuantity()).isEqualTo(2_000);
        assertThat(result.updatedAt()).isNotNull();
    }

    private void insertCouponAndMembers() {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES "
                        + "(1001, 'member1@example.com', '회원1', '01000000001'), "
                        + "(1002, 'member2@example.com', '회원2', '01000000002'), "
                        + "(1003, 'member3@example.com', '회원3', '01000000003')");
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status) "
                        + "VALUES (?, '관리자 조회 테스트 쿠폰', "
                        + "CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY, 'OPEN')",
                COUPON_ID);
    }

    private void insertIssue(long issueId, long memberId, String issuedAt) {
        insertIssue(issueId, memberId, issuedAt, null, null);
    }

    private void insertIssue(
            long issueId,
            long memberId,
            String issuedAt,
            Long issueSequence,
            Long remainingAtIssue) {
        LocalDateTime issuedDateTime = LocalDateTime.parse(issuedAt.replace(' ', 'T'));
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(coupon_issue_id, coupon_id, member_id, issue_sequence, remaining_at_issue, "
                        + "status, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ISSUED', ?, ?)",
                issueId,
                COUPON_ID,
                memberId,
                issueSequence,
                remainingAtIssue,
                issuedDateTime,
                issuedDateTime.plusDays(7));
    }
}
