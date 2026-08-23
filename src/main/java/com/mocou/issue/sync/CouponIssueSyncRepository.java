package com.mocou.issue.sync;

import java.time.LocalDateTime;
import java.util.List;

import com.mocou.global.exception.ErrorCode;

/**
 * Redis Stream → DB 동기화 컨슈머가 사용하는 저장소.
 *
 * <p>Issue #39 체크리스트 2번(OPEN 상태 쿠폰 목록 조회) 담당. 컨슈머는 이 목록으로
 * "이번 실행에서 어떤 couponId의 Stream을 읽어야 하는지"를 결정한다. Redis 쪽에
 * 별도로 "지금 열려 있는 쿠폰 목록"을 관리하는 곳이 없어서, OPEN 여부는 항상 이
 * 저장소(DB)를 기준으로 조회한다.
 */
public interface CouponIssueSyncRepository {

    /**
     * status가 'OPEN'인 쿠폰의 coupon_id 목록을 반환한다.
     *
     * <p>컨슈머는 이 메서드를 스케줄 틱(기본 10ms)마다가 아니라, 시작 시 한 번과
     * {@code CouponStatusChangedEvent}(coupon.status를 바꾸는 쪽, 예: 관리자 API가
     * 발행)를 받을 때만 호출해 캐시를 갱신한다 — 매 틱 DB를 조회하면 그 자체가
     * 병목이 되기 때문이다.
     */
    List<Long> findOpenCouponIds();

    /**
     * 이벤트 목록을 하나의 트랜잭션으로 {@code coupon_issue}/{@code coupon_issue_history}에
     * 반영하고, 실제로 새로 저장된 건수만큼 {@code coupon_stock}을 차감한다.
     *
     * <p>이미 처리된(재전달된) 이벤트는 {@code coupon_issue}의 UNIQUE(coupon_id, member_id)
     * 제약에 걸려 조용히 skip된다 — 예외를 던지지 않고 정상 반환하므로, 호출부는 반환 후
     * 배치 전체를 안전하게 XACK할 수 있다.
     *
     * @return 실제로 새로 저장된(= 재전달 skip이 아닌) 이벤트 목록. 이 메서드가 커밋까지
     * 끝낸 뒤 반환하므로, 호출부는 이 목록만 발급 성공 알림 대상으로 삼으면 된다.
     */
    List<CouponIssueSyncEvent> saveBatch(long couponId, List<CouponIssueSyncEvent> events);

    /**
     * 재시도 한도(maxDeliveryCount)를 넘겨 더 이상 재처리하지 않기로 포기한 이벤트를
     * {@code issue_failure_log}에 남긴다. Redis 재고 보상(compensate)과 짝을 이루는
     * 호출로, 컨슈머는 이 저장소 호출 전에 이미 보상을 마친 상태다.
     */
    void recordFailure(long couponId, long memberId, ErrorCode failureReason, LocalDateTime occurredAt);
}
