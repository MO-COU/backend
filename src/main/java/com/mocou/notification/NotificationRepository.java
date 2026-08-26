package com.mocou.notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code notification} 테이블에 대한 알림 영속성을 전부 담당하는 단일 리포지토리.
 * 큐잉(save)과 발송 폴링(findPending/markSent/incrementRetryCount/markFailed)이 전부
 * "알림 하나가 어떻게 되는지"라는 같은 흐름의 단계일 뿐이라, 성공/실패나 쓰기/읽기로
 * 나눠 여러 리포지토리를 둘 이유가 없다. 실패 여부는 별도 로그 테이블 없이
 * {@code status = 'FAILED'}로만 표현한다.
 */
public interface NotificationRepository {

    /** outbox: 새 알림을 PENDING으로 큐잉한다. */
    void save(NotificationRecord notification);

    /** outbox: 아직 발송 안 된(PENDING) 알림을 오래된 순으로 가져온다. */
    List<PendingNotification> findPending(int limit);

    void markSent(long notificationId, LocalDateTime sentAt);

    void incrementRetryCount(long notificationId);

    /** 재시도 한도를 넘겨 더 이상 재시도하지 않기로 확정된 최종 실패. */
    void markFailed(long notificationId);
}
