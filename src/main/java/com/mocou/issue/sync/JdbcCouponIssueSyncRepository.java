package com.mocou.issue.sync;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.mocou.global.exception.ErrorCode;
import com.mocou.notification.NotificationSender;
import com.mocou.notification.NotificationType;

import lombok.RequiredArgsConstructor;

/*
 * JdbcClient 기반 구현. JdbcCouponRedisInitializationRepository와 동일 스타일
 * (순수 SQL + JdbcClient) — 이 프로젝트는 아직 JPA 엔티티를 안 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcCouponIssueSyncRepository implements CouponIssueSyncRepository {

    private static final String FIND_OPEN_COUPON_IDS = """
            SELECT coupon_id
            FROM coupon
            WHERE status = 'OPEN'
            """;

    private static final String INSERT_COUPON_ISSUE = """
            INSERT INTO coupon_issue (
                coupon_id, member_id, issue_sequence, remaining_at_issue, status, issued_at, expires_at)
            VALUES (:couponId, :memberId, :issueSequence, :remainingAtIssue, 'ISSUED', :issuedAt, :expiresAt)
            """;

    private static final String INSERT_COUPON_ISSUE_HISTORY = """
            INSERT INTO coupon_issue_history (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)
            VALUES (:couponIssueId, 'UNISSUED', 'ISSUED', :changedAt, :idempotencyKey)
            """;

    private static final String DECREASE_COUPON_STOCK = """
            UPDATE coupon_stock
            SET remaining_quantity = remaining_quantity - :count
            WHERE coupon_id = :couponId
            """;

    private static final String INSERT_ISSUE_FAILURE_LOG = """
            INSERT INTO issue_failure_log (coupon_id, member_id, failure_reason, occurred_at)
            VALUES (:couponId, :memberId, :failureReason, :occurredAt)
            """;

    /* coupon-lifecycle-policy.md: expires_at = issued_at + 14일 */
    private static final int EXPIRATION_DAYS = 14;

    private static final String IDEMPOTENCY_KEY_PREFIX = "ISSUE:";

    private final JdbcClient jdbcClient;
    // outbox: 저장/실패 기록과 같은 트랜잭션 안에서 알림을 PENDING으로 큐잉하기 위해 주입.
    private final NotificationSender notificationSender;

    @Override
    public List<Long> findOpenCouponIds() {
        return jdbcClient.sql(FIND_OPEN_COUPON_IDS)
                .query(Long.class)
                .list();
    }

    /*
     * UNIQUE(coupon_id, member_id) 위반만 DuplicateKeyException으로 잡아 skip —
     * 이 catch가 @Transactional 경계를 안 넘으므로 트랜잭션은 rollback-only가
     * 안 되고, 배치의 나머지 이벤트도 같은 트랜잭션에서 계속 처리된다.
     */
    @Override
    @Transactional
    public List<CouponIssueSyncEvent> saveBatch(long couponId, List<CouponIssueSyncEvent> events) {
        List<CouponIssueSyncEvent> savedEvents = new ArrayList<>();
        for (CouponIssueSyncEvent event : events) {
            if (saveOne(event)) {
                savedEvents.add(event);
            }
        }

        // skip된 건 예전 saveBatch에서 이미 재고를 차감했으므로 카운트에서 제외 —
        // 안 그러면 같은 발급 1건을 두 번 빼는 이중 차감 버그가 된다.
        if (!savedEvents.isEmpty()) {
            jdbcClient.sql(DECREASE_COUPON_STOCK)
                    .param("count", savedEvents.size())
                    .param("couponId", couponId)
                    .update();
        }

        // outbox: 이 트랜잭션 안에서 큐잉해야 "커밋은 됐는데 알림 큐잉이 안 된" 크래시 갭이 없다.
        // 한 번에 여러 건이 자연스럽게 발생하는 곳이라 벌크 메서드를 쓴다 — 큐잉 자체는
        // 건별 insert지만(유니크 제약 skip이 건별로 걸림), 발송 성공 후 SENT 갱신은
        // 이 배치 전체가 한 번에 묶여 나간다.
        if (!savedEvents.isEmpty()) {
            notificationSender.notifyMembers(
                    NotificationType.ISSUE_SUCCESS,
                    couponId,
                    savedEvents.stream().map(CouponIssueSyncEvent::memberId).toList());
        }

        return savedEvents;
    }

    // outbox: issue_failure_log와 알림 큐잉을 원자적으로 묶으려고 트랜잭션을 새로 건다
    // (이전엔 이 메서드가 트랜잭션 없이 단독 insert였다).
    @Override
    @Transactional
    public void recordFailure(long couponId, long memberId, ErrorCode failureReason, LocalDateTime occurredAt) {
        jdbcClient.sql(INSERT_ISSUE_FAILURE_LOG)
                .param("couponId", couponId)
                .param("memberId", memberId)
                .param("failureReason", failureReason.name())
                .param("occurredAt", occurredAt)
                .update();
        notificationSender.notifyMember(NotificationType.ISSUE_FAILED, couponId, memberId);
        notificationSender.notifyAdmin(NotificationType.ISSUE_SYNC_FAILED, couponId);
    }

    // recordFailure와 달리 알림을 보내지 않는다 — 아직 최종 실패가 아니라 DLQ 복구를
    // 시도하는 중이다.
    @Override
    @Transactional
    public void recordRetryEscalation(long couponId, long memberId, ErrorCode reason, LocalDateTime occurredAt) {
        jdbcClient.sql(INSERT_ISSUE_FAILURE_LOG)
                .param("couponId", couponId)
                .param("memberId", memberId)
                .param("failureReason", reason.name())
                .param("occurredAt", occurredAt)
                .update();
    }

    /** @return 새로 저장했으면 true, 재전달된 중복이라 skip했으면 false */
    private boolean saveOne(CouponIssueSyncEvent event) {
        try {
            // issuedAt은 컨슈머 처리 시각이 아니라 Redis 예약 확정 시각 — 그래야
            // 컨슈머가 늦게 처리해도 만료 시점이 밀리지 않는다.
            LocalDateTime expiresAt = event.issuedAt().plusDays(EXPIRATION_DAYS);

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcClient.sql(INSERT_COUPON_ISSUE)
                    .param("couponId", event.couponId())
                    .param("memberId", event.memberId())
                    .param("issueSequence", event.issueSequence())
                    .param("remainingAtIssue", event.remainingAtIssue())
                    .param("issuedAt", event.issuedAt())
                    .param("expiresAt", expiresAt)
                    .update(keyHolder);
            // UNIQUE 위반이면 여기서 DuplicateKeyException → 아래 catch로.

            long couponIssueId = keyHolder.getKey().longValue();

            // UNISSUED → ISSUED 최초 이력, 멱등키 "ISSUE:{couponIssueId}".
            jdbcClient.sql(INSERT_COUPON_ISSUE_HISTORY)
                    .param("couponIssueId", couponIssueId)
                    .param("changedAt", event.issuedAt())
                    .param("idempotencyKey", IDEMPOTENCY_KEY_PREFIX + couponIssueId)
                    .update();

            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
