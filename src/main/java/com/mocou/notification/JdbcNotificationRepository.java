package com.mocou.notification;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /*
     * uk_notification_target(coupon_id, member_id, type) 위반은 "이미 같은 알림이
     * 큐잉/발송된 적 있다"는 뜻이라 조용히 skip한다 — 호출부(saveBatch/recordFailure 등,
     * CouponIssueSyncRepository 쪽 issue_failure_log 기록)의 트랜잭션까지 롤백시키면
     * 안 되므로 여기서 잡는다. member_id/coupon_id가 NULL인 관리자 알림은 이 제약에
     * 안 걸려 정상적으로 여러 번 쌓인다.
     */
    @Override
    public void save(NotificationRecord notification) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO notification (coupon_id, member_id, type, status, sent_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    notification.couponId(),
                    notification.memberId(),
                    notification.type().name(),
                    notification.status().name(),
                    notification.sentAt() == null ? null : Timestamp.valueOf(notification.sentAt()));
        } catch (DuplicateKeyException e) {
            // no-op: 이미 큐잉된 알림과 동일한 대상이므로 추가로 할 일이 없다.
        }
    }

    @Override
    public List<PendingNotification> findPending(int limit) {
        return jdbcTemplate.query(
                "SELECT notification_id, coupon_id, member_id, type, retry_count "
                        + "FROM notification WHERE status = 'PENDING' "
                        + "ORDER BY notification_id LIMIT ?",
                (rs, rowNum) ->
                        new PendingNotification(
                                rs.getLong("notification_id"),
                                (Long) rs.getObject("coupon_id"),
                                (Long) rs.getObject("member_id"),
                                NotificationType.valueOf(rs.getString("type")),
                                rs.getInt("retry_count")),
                limit);
    }

    @Override
    public void markSent(long notificationId, LocalDateTime sentAt) {
        jdbcTemplate.update(
                "UPDATE notification SET status = 'SENT', sent_at = ? WHERE notification_id = ?",
                Timestamp.valueOf(sentAt),
                notificationId);
    }

    @Override
    public void incrementRetryCount(long notificationId) {
        jdbcTemplate.update(
                "UPDATE notification SET retry_count = retry_count + 1 WHERE notification_id = ?",
                notificationId);
    }

    @Override
    public void markFailed(long notificationId) {
        jdbcTemplate.update(
                "UPDATE notification SET status = 'FAILED' WHERE notification_id = ?", notificationId);
    }
}
