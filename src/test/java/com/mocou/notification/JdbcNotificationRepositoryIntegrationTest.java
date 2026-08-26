package com.mocou.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mocou.support.MySqlContainerTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class JdbcNotificationRepositoryIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;
    private static final long MEMBER_ID_2 = 1002L;

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

    // outbox: 성공(pending->sent)만 묶어서 갱신하는 게 이번 배치 리팩터링의 핵심이라,
    // 여러 건을 한 번에 SENT로 확정해도 각 건이 정확히 반영되는지 실제 DB로 검증한다.
    @Test
    @DisplayName("여러 알림을 한 번에 SENT로 확정하면 모두 반영되고 나머지는 PENDING으로 남는다")
    void marksMultipleNotificationsSentInOneBatch() {
        // given
        insertCouponAndMember(MEMBER_ID);
        insertCouponAndMember(MEMBER_ID_2);
        Long id1 = repository.save(record(MEMBER_ID, NotificationType.ISSUE_SUCCESS));
        Long id2 = repository.save(record(MEMBER_ID_2, NotificationType.ISSUE_SUCCESS));
        Long untouchedId = repository.save(record(MEMBER_ID, NotificationType.USED));

        // when
        repository.markSentBatch(List.of(id1, id2), LocalDateTime.now());

        // then
        assertThat(statusOf(id1)).isEqualTo("SENT");
        assertThat(statusOf(id2)).isEqualTo("SENT");
        assertThat(statusOf(untouchedId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("빈 목록으로 SENT 확정을 호출해도 아무 것도 바뀌지 않는다")
    void markSentBatchWithEmptyListDoesNothing() {
        // given
        insertCouponAndMember(MEMBER_ID);
        Long id = repository.save(record(MEMBER_ID, NotificationType.ISSUE_SUCCESS));

        // when
        assertThatCode(() -> repository.markSentBatch(List.of(), LocalDateTime.now()))
                .doesNotThrowAnyException();

        // then
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    private String statusOf(long notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification WHERE notification_id = ?", String.class, notificationId);
    }

    private NotificationRecord record(long memberId, NotificationType type) {
        return new NotificationRecord(COUPON_ID, memberId, type, NotificationStatus.PENDING, null);
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
        insertCouponAndMember(MEMBER_ID);
    }

    // coupon_id는 여러 회원이 공유하므로 INSERT IGNORE로 두 번째 호출부터는 조용히 건너뛴다.
    private void insertCouponAndMember(long memberId) {
        jdbcTemplate.update(
                "INSERT INTO member (member_id, email, name, phone) VALUES (?, ?, ?, ?)",
                memberId,
                "member" + memberId + "@example.com",
                "유니크 제약 테스트 회원",
                "01000000000");
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO coupon (coupon_id, name, open_at, close_at, status)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 1 DAY, 'OPEN')
                """,
                COUPON_ID,
                "유니크 제약 테스트 쿠폰");
    }
}
