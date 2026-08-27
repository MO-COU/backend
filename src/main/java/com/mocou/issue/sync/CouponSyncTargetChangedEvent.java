package com.mocou.issue.sync;

/**
 * "지금부터 이 couponId가 동기화 컨슈머의 활성 대상이다"를 알리는 이벤트.
 * 부하 테스트 시작({@code LoadTestExecutionService.start})처럼, 특정 쿠폰의 발급
 * 처리를 실제로 시작시키는 쪽이 {@code ApplicationEventPublisher.publishEvent(...)}로
 * 발행한다. 회차를 만드는 것({@code CouponRoundService.create})만으로는 발행하지
 * 않는다 — 회차 생성과 "지금 이 쿠폰을 처리하라"는 서로 다른 결정이며, 후자는
 * 관리자가 별도로 판단해서 트리거해야 한다.
 *
 * <p>여러 쿠폰이 동시에 {@code OPEN} 상태일 수 있지만, 실제로 발급을 처리하는
 * 컨슈머는 항상 하나만 활성화한다(관리자가 순차적으로 하나씩 진행시키는 운영
 * 시나리오). 그래서 이 이벤트의 couponId는 "DB를 다시 조회해서 확인할 대상"이
 * 아니라, 컨슈머가 그대로 신뢰하고 전환할 대상 그 자체다.
 */
public record CouponSyncTargetChangedEvent(long couponId) {
}
