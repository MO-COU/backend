package com.mocou.issue.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.RecordId;

import com.mocou.support.MySqlContainerTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class JdbcCouponIssueSyncRepositoryIntegrationTest
        extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID_1 = 3001L;
    private static final long MEMBER_ID_2 = 3002L;
    private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 20, 10, 0);
    private static final LocalDateTime CLOSE_AT = LocalDateTime.of(2026, 8, 20, 11, 0);
    // 초 단위까지만 비교해도 충분하고, DATETIME 컬럼 왕복 시 나노초 정밀도 차이로
    // 어서션이 깨지는 걸 피하려고 나노초를 아예 안 쓰는 값을 고른다.
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 8, 20, 10, 30, 0);

    @Autowired
    private CouponIssueSyncRepository repository;

    @Test
    @DisplayName("OPEN 상태 쿠폰의 coupon_id만 조회한다")
    void findsOnlyOpenCouponIds() {
        // OPEN 2건 + 나머지 상태(READY/CLOSED/SOLD_OUT) 3건을 함께 넣어서,
        // WHERE status = 'OPEN' 조건이 다른 상태를 실제로 걸러내는지까지 검증한다.
        insertCoupon(1001L, "OPEN");
        insertCoupon(1002L, "OPEN");
        insertCoupon(1003L, "READY");
        insertCoupon(1004L, "CLOSED");
        insertCoupon(1005L, "SOLD_OUT");

        List<Long> result = repository.findOpenCouponIds();

        assertThat(result).containsExactlyInAnyOrder(1001L, 1002L);
    }

    @Test
    @DisplayName("OPEN 상태 쿠폰이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoCouponIsOpen() {
        // 컨슈머가 매 실행마다 이 목록을 순회하므로, 결과가 없을 때 null이
        // 아니라 빈 리스트여야 호출부에서 별도 null 체크 없이 순회할 수 있다.
        insertCoupon(1001L, "READY");

        assertThat(repository.findOpenCouponIds()).isEmpty();
    }

    @Test
    @DisplayName("새 이벤트를 coupon_issue/coupon_issue_history에 저장하고 재고를 차감한다")
    void savesNewEventsAndDecreasesStock() {
        insertCoupon(COUPON_ID, "OPEN");
        insertCouponStock(COUPON_ID, 100);
        insertMember(MEMBER_ID_1);
        insertMember(MEMBER_ID_2);

        repository.saveBatch(
                COUPON_ID,
                List.of(
                        syncEvent(MEMBER_ID_1, "event-1"),
                        syncEvent(MEMBER_ID_2, "event-2")));

        assertThat(issueCount(COUPON_ID)).isEqualTo(2);
        // expires_at = issued_at + 14일(coupon-lifecycle-policy.md) 그대로 저장됐는지 확인
        assertThat(expiresAtOf(COUPON_ID, MEMBER_ID_1)).isEqualTo(ISSUED_AT.plusDays(14));
        assertThat(issuedHistoryCount(COUPON_ID, MEMBER_ID_1)).isEqualTo(1);
        assertThat(remainingStockOf(COUPON_ID)).isEqualTo(98);
    }

    @Test
    @DisplayName("이미 처리된(coupon_id, member_id) 이벤트는 건너뛰고 나머지만 저장한다")
    void skipsAlreadyProcessedEventOnRedelivery() {
        insertCoupon(COUPON_ID, "OPEN");
        insertCouponStock(COUPON_ID, 100);
        insertMember(MEMBER_ID_1);
        insertMember(MEMBER_ID_2);
        // 이전 실행에서 이미 반영된 것처럼 member 1건을 미리 저장해 재전달 상황을 재현한다.
        repository.saveBatch(COUPON_ID, List.of(syncEvent(MEMBER_ID_1, "event-1")));

        // 재전달: member 1(이미 처리됨) + member 2(새 이벤트)가 같은 배치로 다시 들어옴
        repository.saveBatch(
                COUPON_ID,
                List.of(
                        syncEvent(MEMBER_ID_1, "event-1-redelivered"),
                        syncEvent(MEMBER_ID_2, "event-2")));

        assertThat(issueCount(COUPON_ID)).isEqualTo(2);
        // 중복 건 때문에 history가 2건으로 늘어나지 않고 최초 1건만 유지되는지 확인
        assertThat(issuedHistoryCount(COUPON_ID, MEMBER_ID_1)).isEqualTo(1);
        // 재고는 실제로 새로 저장된 1건(member 2)만큼만 차감돼야 한다 — 중복 skip 건까지
        // 차감하면 이미 Redis Lua 단에서 뺀 재고를 DB에서 또 빼는 이중 차감이 된다.
        assertThat(remainingStockOf(COUPON_ID)).isEqualTo(98);
    }

    private CouponIssueSyncEvent syncEvent(long memberId, String eventId) {
        return new CouponIssueSyncEvent(
                RecordId.of("1-1"), COUPON_ID, memberId, eventId, ISSUED_AT);
    }

    private int issueCount(long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?", Integer.class, couponId);
    }

    private int issuedHistoryCount(long couponId, long memberId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM coupon_issue_history h
                JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
                WHERE i.coupon_id = ? AND i.member_id = ?
                AND h.from_status IS NULL AND h.to_status = 'ISSUED'
                """,
                Integer.class,
                couponId,
                memberId);
    }

    private LocalDateTime expiresAtOf(long couponId, long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM coupon_issue WHERE coupon_id = ? AND member_id = ?",
                LocalDateTime.class,
                couponId,
                memberId);
    }

    private int remainingStockOf(long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM coupon_stock WHERE coupon_id = ?",
                Integer.class,
                couponId);
    }

    private void insertMember(long memberId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                memberId,
                "member" + memberId + "@example.com",
                "동기화 테스트 회원",
                "01000000000");
    }

    private void insertCouponStock(long couponId, int remainingQuantity) {
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity) VALUES (?, ?, ?)",
                couponId,
                remainingQuantity,
                remainingQuantity);
    }

    private void insertCoupon(long couponId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO coupon (
                    coupon_id,
                    name,
                    discount_rate,
                    open_at,
                    close_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                couponId,
                "동기화 대상 조회 테스트 쿠폰",
                10,
                OPEN_AT,
                CLOSE_AT,
                status);
    }
}
