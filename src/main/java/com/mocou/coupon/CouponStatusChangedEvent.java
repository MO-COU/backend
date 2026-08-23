package com.mocou.coupon;

/**
 * coupon.status가 바뀌었을 때 발행되는 이벤트. 관리자 API 등 상태를 바꾸는 쪽이
 * {@code ApplicationEventPublisher.publishEvent(...)}로 발행하면, 상태에 관심 있는
 * 쪽(예: 동기화 컨슈머의 OPEN 목록 캐시)이 구독해서 갱신한다.
 *
 * <p>couponId만 담아 "무엇이 바뀌었는지" 최소한만 알리고, 실제 최신 상태는
 * 구독자가 필요하면 DB에서 다시 조회한다 — 이벤트 payload와 DB 상태가 어긋날
 * 걱정을 없애기 위함이다.
 */
public record CouponStatusChangedEvent(long couponId) {
}
