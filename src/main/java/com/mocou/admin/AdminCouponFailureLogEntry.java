package com.mocou.admin;

import java.time.LocalDateTime;

/** issue_failure_log에서 최종 실패(INTERNAL_ERROR) 사유만 뽑아 회원별로 보강할 때 쓴다. */
record AdminCouponFailureLogEntry(long memberId, String failureReason, LocalDateTime occurredAt) {
}
