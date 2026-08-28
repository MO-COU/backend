package com.mocou.admin;

/**
 * DLQ 실패 항목 재시도 결과. {@code saved=false}는 실패가 아니라 (coupon_id, member_id)가
 * 이미 존재해 새로 저장할 게 없었다는 뜻이다 - 이미 다른 경로로 해결된 항목을 관리자가
 * 뒤늦게 재시도한 경우다. 어느 쪽이든 failed 스트림에서는 제거된다.
 */
public record AdminCouponDlqRetryResult(long couponId, long memberId, boolean saved) {
}
