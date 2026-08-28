package com.mocou.notification;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource());
    }

    /*
     * uk_notification_target(coupon_id, member_id, type) 위반은 "이미 같은 알림이
     * 큐잉/발송된 적 있다"는 뜻이라 조용히 skip한다 — 호출부(saveBatch/recordFailure 등,
     * CouponIssueSyncRepository 쪽 issue_failure_log 기록)의 트랜잭션까지 롤백시키면
     * 안 되므로 여기서 잡는다. member_id/coupon_id가 NULL인 관리자 알림은 이 제약에
     * 안 걸려 정상적으로 여러 번 쌓인다.
     *
     * <p>그 외 DB 오류(커넥션 끊김 등)는 중복과 달리 진짜 실패이므로,
     * BusinessException(NOTIFICATION_QUEUE_FAILED)으로 명확히 표시해서 다시 던진다 —
     * 호출부의 트랜잭션은 그대로 롤백되면서도 실패 사유가 다른 시스템 오류와 구분된다.
     * 원본 예외 메시지는 GlobalExceptionHandler의 마스킹 정책(F-COM-001)과 같은 이유로
     * BusinessException에 실어 보내지 않고 서버 로그에만 남긴다 - DB 예외 메시지에
     * 유니크 값 등 원본 데이터가 그대로 담기는 경우가 있다.
     */
    @Override
    public Long save(NotificationRecord notification) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(
                    connection -> {
                        PreparedStatement ps = connection.prepareStatement(
                                "INSERT INTO notification (coupon_id, member_id, type, status, sent_at, created_at) "
                                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                                PreparedStatement.RETURN_GENERATED_KEYS);
                        ps.setObject(1, notification.couponId());
                        ps.setObject(2, notification.memberId());
                        ps.setString(3, notification.type().name());
                        ps.setString(4, notification.status().name());
                        if (notification.sentAt() == null) {
                            ps.setNull(5, Types.TIMESTAMP);
                        } else {
                            ps.setTimestamp(5, Timestamp.valueOf(notification.sentAt()));
                        }
                        return ps;
                    },
                    keyHolder);
            return keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            // 이미 큐잉된 알림과 동일한 대상이므로 추가로 할 일이 없다.
            return null;
        } catch (DataAccessException e) {
            log.error("알림 큐잉(insert)에 실패했습니다. type={}, coupon_id={}, member_id={}",
                    notification.type(), notification.couponId(), notification.memberId(), e);
            throw new BusinessException(ErrorCode.NOTIFICATION_QUEUE_FAILED);
        }
    }

    @Override
    public List<PendingNotification> saveBatch(long couponId, NotificationType type, List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }

        List<PendingNotification> queued = tryBatchInsert(couponId, type, memberIds);
        if (queued != null) {
            return queued;
        }

        List<PendingNotification> savedOneByOne = new ArrayList<>();
        for (Long memberId : memberIds) {
            Long notificationId =
                    save(new NotificationRecord(couponId, memberId, type, NotificationStatus.PENDING, null));
            if (notificationId == null) {
                continue;
            }
            savedOneByOne.add(new PendingNotification(notificationId, couponId, memberId, type, 0));
        }
        return savedOneByOne;
    }

    /**
     * notification을 addBatch/executeBatch로 한 번에 넣어보고, 성공하면 방금 넣은
     * notification_id를 다시 조회해 돌려준다.
     *
     * <p>{@code rewriteBatchedStatements=true}(local/prod 데이터소스 URL에 설정됨)에서
     * MySQL 드라이버가 이 addBatch 호출들을 진짜 하나의 multi-row INSERT 문으로
     * 재작성해 보낸다 — 그래서 한 행이라도 uk_notification_target 위반이면 그 문장
     * 전체가 원자적으로 실패하고 아무 행도 반영되지 않는다. 이 전제 덕분에 실패 시
     * 통째로 건별 폴백(save 반복)으로 넘어갈 수 있다.
     *
     * <p>배치 INSERT 자체가 아니라 그 뒤 id 재조회가 실패하는 경우는 여기서 잡아 빈
     * 목록을 돌려준다(건별 폴백으로 넘기지 않는다) — INSERT는 이미 성공해 커밋될
     * 것이므로 데이터는 정상이고, 다만 커밋 직후 즉시 발송 대상 id를 못 얻을 뿐이라
     * 안전망 폴링({@link #findPending})이 나중에 그대로 집어간다. 여기서 건별
     * 폴백으로 넘기면 이미 들어간 행을 save()가 중복으로 오인해 전부 건너뛰고, 즉시
     * 발송 기회 자체가 통째로 사라진다.
     *
     * @return 배치가 전부 성공하면 큐잉된 목록(id 재조회 실패 시 빈 목록), 배치
     *     INSERT 자체가 실패(주로 재전달 중복)하면 null
     */
    private List<PendingNotification> tryBatchInsert(long couponId, NotificationType type, List<Long> memberIds) {
        try {
            List<Object[]> batchArgs = memberIds.stream()
                    .map(memberId -> new Object[] {couponId, memberId, type.name()})
                    .toList();
            jdbcTemplate.batchUpdate(
                    "INSERT INTO notification (coupon_id, member_id, type, status, sent_at, created_at) "
                            + "VALUES (?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP)",
                    batchArgs);
        } catch (DataAccessException exception) {
            return null;
        }

        return findQueued(couponId, type, memberIds);
    }

    private List<PendingNotification> findQueued(long couponId, NotificationType type, List<Long> memberIds) {
        try {
            return namedParameterJdbcTemplate.query(
                    "SELECT notification_id, member_id FROM notification "
                            + "WHERE coupon_id = :couponId AND type = :type AND member_id IN (:memberIds)",
                    new MapSqlParameterSource()
                            .addValue("couponId", couponId)
                            .addValue("type", type.name())
                            .addValue("memberIds", memberIds),
                    (rs, rowNum) -> new PendingNotification(
                            rs.getLong("notification_id"), couponId, rs.getLong("member_id"), type, 0));
        } catch (DataAccessException e) {
            log.warn(
                    "알림 배치 큐잉은 성공했지만 즉시 발송용 id 조회에 실패해 안전망 폴링으로 넘어간다. "
                            + "couponId={}, type={}",
                    couponId, type, e);
            return List.of();
        }
    }

    @Override
    public List<PendingNotification> findPending(int limit, LocalDateTime createdBefore) {
        try {
            return jdbcTemplate.query(
                    "SELECT notification_id, coupon_id, member_id, type, retry_count "
                            + "FROM notification WHERE status = 'PENDING' AND created_at <= ? "
                            + "ORDER BY notification_id LIMIT ?",
                    (rs, rowNum) ->
                            new PendingNotification(
                                    rs.getLong("notification_id"),
                                    (Long) rs.getObject("coupon_id"),
                                    (Long) rs.getObject("member_id"),
                                    NotificationType.valueOf(rs.getString("type")),
                                    rs.getInt("retry_count")),
                    Timestamp.valueOf(createdBefore),
                    limit);
        } catch (DataAccessException e) {
            log.error("PENDING 알림 조회에 실패했습니다.", e);
            throw new BusinessException(ErrorCode.NOTIFICATION_DISPATCH_FAILED);
        }
    }

    @Override
    public NotificationStatusCounts countIssueSuccessByCouponId(long couponId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) AS total_count,
                           COALESCE(SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END), 0) AS sent_count,
                           COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                           COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count
                    FROM notification
                    WHERE coupon_id = ? AND type = 'ISSUE_SUCCESS'
                    """,
                    (rs, rowNum) ->
                            new NotificationStatusCounts(
                                    rs.getLong("total_count"),
                                    rs.getLong("sent_count"),
                                    rs.getLong("pending_count"),
                                    rs.getLong("failed_count")),
                    couponId);
        } catch (DataAccessException e) {
            log.error("회차별 발급 성공 알림 상태 집계에 실패했습니다. couponId={}", couponId, e);
            throw new BusinessException(ErrorCode.NOTIFICATION_DISPATCH_FAILED);
        }
    }

    @Override
    public void markSentBatch(List<Long> notificationIds, LocalDateTime sentAt) {
        if (notificationIds.isEmpty()) {
            return;
        }
        try {
            namedParameterJdbcTemplate.update(
                    "UPDATE notification SET status = 'SENT', sent_at = :sentAt WHERE notification_id IN (:ids)",
                    new MapSqlParameterSource()
                            .addValue("sentAt", Timestamp.valueOf(sentAt))
                            .addValue("ids", notificationIds));
        } catch (DataAccessException e) {
            log.error("알림 SENT 일괄 갱신에 실패했습니다. notificationIds={}", notificationIds, e);
            throw new BusinessException(ErrorCode.NOTIFICATION_DISPATCH_FAILED);
        }
    }

    @Override
    public void incrementRetryCount(long notificationId) {
        try {
            jdbcTemplate.update(
                    "UPDATE notification SET retry_count = retry_count + 1 WHERE notification_id = ?",
                    notificationId);
        } catch (DataAccessException e) {
            log.error("알림 재시도 횟수 갱신에 실패했습니다. notificationId={}", notificationId, e);
            throw new BusinessException(ErrorCode.NOTIFICATION_DISPATCH_FAILED);
        }
    }

    @Override
    public void markFailed(long notificationId) {
        try {
            jdbcTemplate.update(
                    "UPDATE notification SET status = 'FAILED' WHERE notification_id = ?", notificationId);
        } catch (DataAccessException e) {
            log.error("알림 최종 실패(FAILED) 확정에 실패했습니다. notificationId={}", notificationId, e);
            throw new BusinessException(ErrorCode.NOTIFICATION_DISPATCH_FAILED);
        }
    }
}
