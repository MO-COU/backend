package com.mocou.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mocou.support.MySqlContainerTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class JdbcNotificationRepositoryIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;

    @Autowired private NotificationRepository repository;

    @Test
    @DisplayName("같은 (coupon, member, type) 알림을 두 번 저장해도 한 건만 남는다")
    void deduplicatesSameTargetAndType() {
        // given
        insertCouponAndMember();

        // when
        assertThatCode(
                        () -> {
                            repository.save(record(NotificationType.ISSUE_SUCCESS));
                            repository.save(record(NotificationType.ISSUE_SUCCESS));
                        })
                .doesNotThrowAnyException();

        // then
        assertThat(notificationCount(MEMBER_ID, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 대상이라도 알림 종류가 다르면 각각 저장된다")
    void allowsDifferentTypesForSameTarget() {
        // given
        insertCouponAndMember();

        // when
        repository.save(record(NotificationType.ISSUE_SUCCESS));
        repository.save(record(NotificationType.USED));

        // then
        assertThat(notificationCount(MEMBER_ID, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
        assertThat(notificationCount(MEMBER_ID, NotificationType.USED)).isEqualTo(1);
    }

    @Test
    @DisplayName("member_id가 없는 관리자 알림은 같은 종류라도 반복 저장된다")
    void doesNotDeduplicateAdminNotificationsWithoutMemberId() {
        // given
        insertCouponAndMember();

        // when
        repository.save(
                new NotificationRecord(
                        COUPON_ID, null, NotificationType.STOCK_DEPLETED, NotificationStatus.PENDING, null));
        repository.save(
                new NotificationRecord(
                        COUPON_ID, null, NotificationType.STOCK_DEPLETED, NotificationStatus.PENDING, null));

        // then
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notification "
                                        + "WHERE coupon_id = ? AND member_id IS NULL AND type = ?",
                                Integer.class,
                                COUPON_ID,
                                NotificationType.STOCK_DEPLETED.name()))
                .isEqualTo(2);
    }

    private NotificationRecord record(NotificationType type) {
        return new NotificationRecord(COUPON_ID, MEMBER_ID, type, NotificationStatus.PENDING, null);
    }

    private int notificationCount(long memberId, NotificationType type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE coupon_id = ? AND member_id = ? AND type = ?",
                Integer.class,
                COUPON_ID,
                memberId,
                type.name());
    }

    private void insertCouponAndMember() {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                MEMBER_ID,
                "member" + MEMBER_ID + "@example.com",
                "유니크 제약 테스트 회원",
                "01000000000");
        jdbcTemplate.update(
                """
                INSERT INTO coupon (coupon_id, name, open_at, close_at, status)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 1 DAY, 'OPEN')
                """,
                COUPON_ID,
                "유니크 제약 테스트 쿠폰");
    }
}
