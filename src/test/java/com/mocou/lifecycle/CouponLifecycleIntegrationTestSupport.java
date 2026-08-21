package com.mocou.lifecycle;

import com.mocou.support.MySqlContainerTest;
import org.junit.jupiter.api.BeforeEach;

/**
 * 쿠폰 생명주기 통합 테스트만 필요로 하는 픽스처와 정리.
 *
 * <p>발급된 쿠폰 한 건을 만드는 픽스처와, 저장 실패를 재현하려고 만든 트리거를 걷어내는 처리를 담는다. 다른 도메인 테스트에는 필요 없어서 공통
 * 기반이 아니라 이 패키지에 둔다. 테이블 정리는 FK로 엮여 있어 도메인별로 나눌 수 없으므로 공통 기반이 담당한다.
 */
abstract class CouponLifecycleIntegrationTestSupport extends MySqlContainerTest {

    private static final long FIXTURE_MEMBER_ID = 1001L;
    private static final long FIXTURE_COUPON_ID = 2001L;

    @BeforeEach
    void removeUseFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_used_history");
    }

    protected void insertIssuedCoupon(long issueId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                FIXTURE_MEMBER_ID,
                "member@example.com",
                "테스트 회원",
                "01000000000");
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL 1 DAY, "
                        + "CURRENT_TIMESTAMP + INTERVAL 1 DAY, ?)",
                FIXTURE_COUPON_ID,
                "테스트 쿠폰",
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
                        + "VALUES (?, 'UNISSUED', 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, ?)",
                issueId,
                "ISSUE:" + issueId);
    }
}
