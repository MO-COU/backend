package com.mocou.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.support.MySqlContainerTest;

@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class JdbcNotificationRepositoryIntegrationTest extends MySqlContainerTest {

    private static final long COUPON_ID = 2001L;
    private static final long MEMBER_ID = 1001L;
    private static final long MEMBER_ID_2 = 1002L;
    private static final long MEMBER_ID_3 = 1003L;

    @Autowired private NotificationRepository repository;

    // 이 클래스가 만드는 트리거가 다른 테스트(같은 컨테이너를 공유)로 새지 않게 앞뒤로 정리한다.
    @BeforeEach
    @AfterEach
    void removeForcedFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_notification_insert");
    }

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

    // outbox: 커밋 직후 즉시 발송 경로와의 중복을 막는 핵심 조건 - 방금 큐잉된(=아직 즉시
    // 경로가 처리 중일 수 있는) row는 폴링이 절대 집어가면 안 된다.
    @Test
    @DisplayName("createdBefore보다 최근에 생성된 PENDING은 조회되지 않는다")
    void excludesPendingNotificationsCreatedAfterCutoff() {
        // given
        insertCouponAndMember();
        Long freshId = repository.save(record(NotificationType.ISSUE_SUCCESS));

        // when
        List<PendingNotification> excluded = repository.findPending(10, LocalDateTime.now().minusSeconds(5));

        // then
        assertThat(excluded).extracting(PendingNotification::notificationId).doesNotContain(freshId);
    }

    @Test
    @DisplayName("createdBefore보다 오래된 PENDING은 정상적으로 조회된다")
    void findsPendingNotificationsCreatedBeforeCutoff() {
        // given
        insertCouponAndMember();
        Long oldId = repository.save(record(NotificationType.ISSUE_SUCCESS));
        jdbcTemplate.update(
                "UPDATE notification SET created_at = CURRENT_TIMESTAMP - INTERVAL 10 SECOND "
                        + "WHERE notification_id = ?",
                oldId);

        // when
        List<PendingNotification> found = repository.findPending(10, LocalDateTime.now().minusSeconds(5));

        // then
        assertThat(found).extracting(PendingNotification::notificationId).contains(oldId);
    }

    @Test
    @DisplayName("회차별 발급 성공 알림 상태만 집계한다")
    void countsIssueSuccessNotificationStatusesByCoupon() {
        insertCouponAndMember(MEMBER_ID);
        insertCouponAndMember(MEMBER_ID_2);
        insertCouponAndMember(MEMBER_ID_3);
        Long sentId = repository.save(record(MEMBER_ID, NotificationType.ISSUE_SUCCESS));
        repository.save(record(MEMBER_ID_2, NotificationType.ISSUE_SUCCESS));
        Long failedId = repository.save(record(MEMBER_ID_3, NotificationType.ISSUE_SUCCESS));
        repository.save(record(MEMBER_ID, NotificationType.USED));
        repository.markSentBatch(List.of(sentId), LocalDateTime.now());
        repository.markFailed(failedId);

        NotificationStatusCounts counts = repository.countIssueSuccessByCouponId(COUPON_ID);

        assertThat(counts).isEqualTo(new NotificationStatusCounts(3, 1, 1, 1));
    }

    @Test
    @DisplayName("발급 성공 알림이 없는 회차는 모든 집계를 0으로 반환한다")
    void returnsZeroCountsWhenIssueSuccessNotificationDoesNotExist() {
        insertCouponAndMember();
        repository.save(record(NotificationType.USED));

        NotificationStatusCounts counts = repository.countIssueSuccessByCouponId(COUPON_ID);

        assertThat(counts).isEqualTo(new NotificationStatusCounts(0, 0, 0, 0));
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

    // outbox: uk_notification_target 중복(DuplicateKeyException)은 조용히 null을 반환해야
    // 하지만, 그 외의 진짜 DB 오류는 원인 불명의 RuntimeException이 아니라
    // BusinessException(NOTIFICATION_QUEUE_FAILED)으로 명확히 구분돼야 한다.
    @Test
    @DisplayName("중복이 아닌 DB 오류로 큐잉이 실패하면 NOTIFICATION_QUEUE_FAILED로 표시된다")
    void wrapsNonDuplicateInsertFailureAsBusinessException() {
        // given
        insertCouponAndMember();
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_notification_insert BEFORE INSERT ON notification "
                        + "FOR EACH ROW SIGNAL SQLSTATE '45000' "
                        + "SET MESSAGE_TEXT = 'forced notification insert failure'");

        // when, then
        assertThatThrownBy(() -> repository.save(record(NotificationType.ISSUE_SUCCESS)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_QUEUE_FAILED));
    }

    // outbox: saveBatch는 addBatch/executeBatch를 먼저 시도한다 - 전부 새 대상이면
    // 이 배치 경로 하나로 끝나야 한다.
    @Test
    @DisplayName("여러 회원을 saveBatch로 큐잉하면 전부 새로 큐잉되고 즉시 발송용 id가 채워진다")
    void queuesNewMembersInOneBatch() {
        // given
        insertCouponAndMember(MEMBER_ID);
        insertCouponAndMember(MEMBER_ID_2);
        insertCouponAndMember(MEMBER_ID_3);

        // when
        List<PendingNotification> queued = repository.saveBatch(
                COUPON_ID, NotificationType.ISSUE_SUCCESS, List.of(MEMBER_ID, MEMBER_ID_2, MEMBER_ID_3));

        // then
        assertThat(queued)
                .extracting(PendingNotification::memberId)
                .containsExactlyInAnyOrder(MEMBER_ID, MEMBER_ID_2, MEMBER_ID_3);
        assertThat(queued).extracting(PendingNotification::notificationId).doesNotContainNull();
        assertThat(notificationCount(MEMBER_ID, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
        assertThat(notificationCount(MEMBER_ID_2, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
        assertThat(notificationCount(MEMBER_ID_3, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
    }

    // outbox: 재전달 등으로 대상 중 일부가 이미 큐잉돼 있으면(uk_notification_target 위반)
    // 배치 INSERT 문 전체가 실패하므로 건별 save()로 폴백해 새 대상만 정확히 큐잉해야 한다.
    @Test
    @DisplayName("이미 큐잉된 대상이 섞여 있으면 배치가 실패해 건별 폴백으로 새 대상만 큐잉한다")
    void fallsBackToOneByOneWhenBatchHasDuplicateTarget() {
        // given
        insertCouponAndMember(MEMBER_ID);
        insertCouponAndMember(MEMBER_ID_2);
        insertCouponAndMember(MEMBER_ID_3);
        repository.save(record(MEMBER_ID_2, NotificationType.ISSUE_SUCCESS));

        // when
        List<PendingNotification> queued = repository.saveBatch(
                COUPON_ID, NotificationType.ISSUE_SUCCESS, List.of(MEMBER_ID, MEMBER_ID_2, MEMBER_ID_3));

        // then
        assertThat(queued)
                .extracting(PendingNotification::memberId)
                .containsExactlyInAnyOrder(MEMBER_ID, MEMBER_ID_3);
        assertThat(notificationCount(MEMBER_ID, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
        assertThat(notificationCount(MEMBER_ID_2, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
        assertThat(notificationCount(MEMBER_ID_3, NotificationType.ISSUE_SUCCESS)).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 회원 목록으로 saveBatch를 호출하면 아무 것도 큐잉하지 않는다")
    void saveBatchWithEmptyMemberListDoesNothing() {
        assertThat(repository.saveBatch(COUPON_ID, NotificationType.ISSUE_SUCCESS, List.of())).isEmpty();
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
