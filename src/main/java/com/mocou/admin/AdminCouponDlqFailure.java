package com.mocou.admin;

import java.time.LocalDateTime;

/**
 * DLQ 복구 재시도까지 소진해 {@code issue-dlq-failed} Stream으로 옮겨진 발급 이벤트.
 * failureReason/occurredAt은 issue_failure_log 기록으로 보강한 값이라, DB 장애로 그
 * 기록 자체가 실패했다면 null일 수 있다 — 그런 경우에도 Redis 쪽 항목은 그대로 보인다.
 */
public record AdminCouponDlqFailure(
        String recordId,
        long couponId,
        long memberId,
        String eventId,
        long issueSequence,
        long remainingAtIssue,
        LocalDateTime issuedAt,
        String failureReason,
        LocalDateTime occurredAt) {

    AdminCouponDlqFailure withFailureLog(AdminCouponFailureLogEntry logEntry) {
        if (logEntry == null) {
            return this;
        }
        return new AdminCouponDlqFailure(
                recordId, couponId, memberId, eventId, issueSequence, remainingAtIssue, issuedAt,
                logEntry.failureReason(), logEntry.occurredAt());
    }
}
