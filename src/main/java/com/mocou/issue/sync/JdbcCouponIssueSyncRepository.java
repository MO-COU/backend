package com.mocou.issue.sync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    // JdbcTemplate.batchUpdate는 이름 있는 파라미터를 지원하지 않아 위치 파라미터로 따로 둔다.
    private static final String INSERT_COUPON_ISSUE_BATCH = """
            INSERT INTO coupon_issue (
                coupon_id, member_id, issue_sequence, remaining_at_issue, status, issued_at, expires_at)
            VALUES (?, ?, ?, ?, 'ISSUED', ?, ?)
            """;

    private static final String INSERT_COUPON_ISSUE_HISTORY = """
            INSERT INTO coupon_issue_history (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)
            VALUES (:couponIssueId, 'UNISSUED', 'ISSUED', :changedAt, :idempotencyKey)
            """;

    private static final String INSERT_COUPON_ISSUE_HISTORY_BATCH = """
            INSERT INTO coupon_issue_history (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)
            VALUES (?, 'UNISSUED', 'ISSUED', ?, ?)
            """;

    private static final String FIND_COUPON_ISSUE_IDS_BY_MEMBER = """
            SELECT coupon_issue_id, member_id FROM coupon_issue
            WHERE coupon_id = :couponId AND member_id IN (:memberIds)
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
    // 배치 INSERT(addBatch/executeBatch)는 JdbcClient에 없어 JdbcTemplate을 별도로 쓴다.
    private final JdbcTemplate jdbcTemplate;
    // outbox: 저장/실패 기록과 같은 트랜잭션 안에서 알림을 PENDING으로 큐잉하기 위해 주입.
    private final NotificationSender notificationSender;

    @Override
    public List<Long> findOpenCouponIds() {
        return jdbcClient.sql(FIND_OPEN_COUPON_IDS)
                .query(Long.class)
                .list();
    }

    /*
     * addBatch/executeBatch로 한 번에 시도하고, 실패하면(주로 재전달 중복) 이전과
     * 동일한 건별 처리로 폴백한다 - 자세한 이유는 trySaveBatch 주석 참조.
     */
    @Override
    @Transactional
    public List<CouponIssueSyncEvent> saveBatch(long couponId, List<CouponIssueSyncEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<CouponIssueSyncEvent> savedEvents = trySaveBatch(couponId, events);
        if (savedEvents == null) {
            savedEvents = saveOneByOne(events);
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
        // 한 번에 여러 건이 자연스럽게 발생하는 곳이라 벌크 메서드를 쓴다 — notifyMembers도
        // 배치 우선 시도 + 건별 폴백 구조라 여기와 동일한 원리로 왕복을 줄인다.
        if (!savedEvents.isEmpty()) {
            notificationSender.notifyMembers(
                    NotificationType.ISSUE_SUCCESS,
                    couponId,
                    savedEvents.stream().map(CouponIssueSyncEvent::memberId).toList());
        }

        return savedEvents;
    }

    /**
     * coupon_issue를 addBatch/executeBatch로 한 번에 넣어보고, 성공하면 방금 넣은 행의
     * coupon_issue_id를 다시 조회해 coupon_issue_history도 배치로 채운다.
     *
     * <p>{@code rewriteBatchedStatements=true}(local/prod 데이터소스 URL에 설정됨)에서
     * MySQL 드라이버가 이 addBatch 호출들을 진짜 하나의 multi-row INSERT 문으로
     * 재작성해 보낸다 — 그래서 한 행이라도 UNIQUE(coupon_id, member_id) 위반이면 그
     * 문장 전체가 원자적으로 실패하고 아무 행도 반영되지 않는다. 이 전제 덕분에 실패
     * 시 "일부만 반영된" 상태 걱정 없이 통째로 건별 폴백으로 넘어갈 수 있다. 이 설정이
     * 빠지면 이 가정이 깨지니 반드시 유지해야 한다.
     *
     * <p>coupon_issue 배치가 이미 성공한 뒤(즉 이 메서드가 null을 반환하지 않기로 확정된
     * 뒤)에 일어나는 실패(id 재조회, history 배치)는 여기서 잡지 않고 그대로 던진다 —
     * 그 시점에 폴백하면 방금 넣은 coupon_issue 행을 saveOne이 "이미 처리된 재전달
     * 중복"으로 오인해 건너뛰어 버려서 coupon_issue_history를 영영 못 채우게 된다.
     *
     * @return 배치가 전부 성공하면 저장된 이벤트 목록, 실패(주로 재전달 중복)하면 null
     */
    private List<CouponIssueSyncEvent> trySaveBatch(long couponId, List<CouponIssueSyncEvent> events) {
        if (!tryBatchInsertCouponIssue(events)) {
            return null;
        }

        Map<Long, Long> couponIssueIdByMemberId = findCouponIssueIds(couponId, events);
        batchInsertCouponIssueHistory(events, couponIssueIdByMemberId);
        return new ArrayList<>(events);
    }

    private boolean tryBatchInsertCouponIssue(List<CouponIssueSyncEvent> events) {
        try {
            List<Object[]> batchArgs = events.stream()
                    .map(event -> new Object[] {
                            event.couponId(),
                            event.memberId(),
                            event.issueSequence(),
                            event.remainingAtIssue(),
                            Timestamp.valueOf(event.issuedAt()),
                            Timestamp.valueOf(event.issuedAt().plusDays(EXPIRATION_DAYS))
                    })
                    .toList();
            jdbcTemplate.batchUpdate(INSERT_COUPON_ISSUE_BATCH, batchArgs);
            return true;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private Map<Long, Long> findCouponIssueIds(long couponId, List<CouponIssueSyncEvent> events) {
        List<Long> memberIds = events.stream().map(CouponIssueSyncEvent::memberId).toList();
        return jdbcClient.sql(FIND_COUPON_ISSUE_IDS_BY_MEMBER)
                .param("couponId", couponId)
                .param("memberIds", memberIds)
                .query((rs, rowNum) -> Map.entry(rs.getLong("member_id"), rs.getLong("coupon_issue_id")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void batchInsertCouponIssueHistory(
            List<CouponIssueSyncEvent> events, Map<Long, Long> couponIssueIdByMemberId) {
        List<Object[]> batchArgs = events.stream()
                .map(event -> {
                    long couponIssueId = couponIssueIdByMemberId.get(event.memberId());
                    return new Object[] {
                            couponIssueId,
                            Timestamp.valueOf(event.issuedAt()),
                            IDEMPOTENCY_KEY_PREFIX + couponIssueId
                    };
                })
                .toList();
        jdbcTemplate.batchUpdate(INSERT_COUPON_ISSUE_HISTORY_BATCH, batchArgs);
    }

    private List<CouponIssueSyncEvent> saveOneByOne(List<CouponIssueSyncEvent> events) {
        List<CouponIssueSyncEvent> savedEvents = new ArrayList<>();
        for (CouponIssueSyncEvent event : events) {
            if (saveOne(event)) {
                savedEvents.add(event);
            }
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
