package com.mocou.lifecycle;

import com.mocou.support.MySqlContainerTest;
import org.junit.jupiter.api.BeforeEach;

/**
 * 쿠폰 생명주기 통합 테스트가 공유하는 데이터 정리와 픽스처.
 *
 * <p>쿠폰 계열 테이블 전체를 비우고, 실패 상황 재현용 트리거를 걷어낸다. 발급된 쿠폰 한 건을 만드는 픽스처도 여기서 제공한다. 다른 도메인
 * 테스트에는 필요 없는 동작이라 공통 기반이 아니라 이 패키지에 둔다.
 */
abstract class CouponLifecycleIntegrationTestSupport extends MySqlContainerTest {

    private static final long FIXTURE_MEMBER_ID = 1001L;
    private static final long FIXTURE_COUPON_ID = 2001L;

    @BeforeEach
    void resetCouponData() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_used_history");
        jdbcTemplate.update("DELETE FROM coupon_issue_history");
        jdbcTemplate.update("DELETE FROM coupon_issue");
        jdbcTemplate.update("DELETE FROM coupon_stock");
        jdbcTemplate.update("DELETE FROM coupon");
        jdbcTemplate.update("DELETE FROM member");
    }

    protected void insertIssuedCoupon(long issueId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                FIXTURE_MEMBER_ID,
                "member@example.com",
                "테스트 회원",
                "01000000000");
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, discount_rate, open_at, close_at, status) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY, ?)",
                FIXTURE_COUPON_ID,
                "테스트 쿠폰",
                10,
                "OPEN");
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(coupon_issue_id, coupon_id, member_id, status, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY)",
                issueId,
                FIXTURE_COUPON_ID,
                FIXTURE_MEMBER_ID);
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history "
                        + "(coupon_issue_id, from_status, to_status, changed_at, idempotency_key) "
                        + "VALUES (?, NULL, 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, ?)",
                issueId,
                "ISSUE:" + issueId);
    }
}
