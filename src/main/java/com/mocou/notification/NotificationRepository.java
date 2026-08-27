package com.mocou.notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code notification} 테이블에 대한 알림 영속성을 전부 담당하는 단일 리포지토리.
 * 큐잉(save)과 발송 폴링(findPending/markSentBatch/incrementRetryCount/markFailed)이
 * 전부 "알림 하나가 어떻게 되는지"라는 같은 흐름의 단계일 뿐이라, 성공/실패나 쓰기/읽기로
 * 나눠 여러 리포지토리를 둘 이유가 없다. 실패 여부는 별도 로그 테이블 없이
 * {@code status = 'FAILED'}로만 표현한다.
 *
 * <p>성공(PENDING→SENT)만 {@link #markSentBatch}로 묶어서 갱신한다 — 대부분의 알림이
 * 성공하므로 이 경로가 DB 왕복이 잦은 hot path다. 재시도/최종실패는 드물게 발생하고
 * 건마다 사유(재시도 카운트)가 달라 묶을 실익이 적어 단건 함수로 충분하다.
 */
public interface NotificationRepository {

    /**
     * outbox: 새 알림을 PENDING으로 큐잉한다.
     *
     * @return 새로 큐잉됐으면 생성된 notification_id, uk_notification_target 중복이라
     *     skip됐으면 {@code null} (커밋 직후 즉시 발송을 시도하려면 이 id가 필요하다).
     */
    Long save(NotificationRecord notification);

    /** outbox: 아직 발송 안 된(PENDING) 알림을 오래된 순으로 가져온다. */
    List<PendingNotification> findPending(int limit);

    /** 관리자 화면에 보여줄 회차별 발급 성공 알림 처리 상태를 집계한다. */
    NotificationStatusCounts countIssueSuccessByCouponId(long couponId);

    /** 발송에 성공한 알림들을 한 번에 SENT로 확정한다. {@code notificationIds}가 비어 있으면 아무 것도 하지 않는다. */
    void markSentBatch(List<Long> notificationIds, LocalDateTime sentAt);

    void incrementRetryCount(long notificationId);

    /** 재시도 한도를 넘겨 더 이상 재시도하지 않기로 확정된 최종 실패. */
    void markFailed(long notificationId);
}
