package com.mocou.notification;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(NotificationRecord notification) {
        jdbcTemplate.update(
                "INSERT INTO notification (coupon_id, member_id, type, status, sent_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                notification.couponId(),
                notification.memberId(),
                notification.type().name(),
                notification.status().name(),
                notification.sentAt() == null ? null : Timestamp.valueOf(notification.sentAt()));
    }
}
