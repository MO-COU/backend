package com.mocou.issue.sync;

/**
 * 회차 하나가 지워졌다는 사실. 회차를 지우는 쪽이 발행하고 동기화 쪽이 받는다.
 *
 * <p>{@link CouponSyncTargetChangedEvent}와 같은 자리에 둔다. 듣는 쪽 패키지가 자기가 받을 이벤트를
 * 정의하면 {@code coupon}과 {@code issue.sync}가 서로를 참조하는 순환이 생기지 않는다.
 *
 * <p>발급 동기화 컨슈머가 이 쿠폰을 활성 대상으로 들고 있으면 대상을 다시 정해야 한다. 그대로 두면
 * 컨슈머가 매 틱 {@code ensureConsumerGroup}을 부르고 {@code NOGROUP}을 감지해 그룹을 다시
 * 만들면서 <b>방금 지운 스트림 키가 되살아난다.</b>
 *
 * <p>다음 대상이 누구인지는 여기서 정하지 않는다. 그건 동기화 쪽의 규칙이라 받는 쪽이 스스로 다시
 * 도출한다. 지워진 쿠폰은 이미 {@code OPEN} 목록에 없으므로 재도출에서 자연스럽게 빠진다.
 */
public record CouponRoundDeletedEvent(long couponId) {}
