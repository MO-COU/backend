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
     * <p>컨슈머는 이 메서드를 스케줄 틱(기본 10ms)마다가 아니라 앱 기동 시 딱 한 번만
     * 호출해 "최초 활성 쿠폰"을 정한다 — 매 틱 DB를 조회하면 그 자체가 병목이 되기
     * 때문이다. 기동 이후 활성 쿠폰이 바뀌는 것은 DB 재조회가 아니라
     * {@code CouponSyncTargetChangedEvent}(발급을 실제로 시작시키는 API가 발행하며,
     * couponId를 그대로 담아 전달)로만 반영한다.
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
     * DLQ 복구마저 자체 재시도 한도를 넘겨 더 이상 재처리하지 않기로 최종 포기한
     * 이벤트를 {@code issue_failure_log}에 남기고 회원과 관리자에게 각각 알림을 보낸다.
     * Redis 재고는 더 이상 여기서 보상하지 않는다 — 예약을 그대로 남겨 관리자가
     * DLQ 실패 목록 조회 API로 확인한 뒤 직접 처리한다.
     *
     * <p>호출부(DLQ 복구 컨슈머)는 이 메서드를 부르기 전에 이미 Redis Stream의
     * failed 큐로 엔트리를 옮겨둔 상태다 — 여기서 예외가 나도(DB 장애 등) Redis
     * 쪽 최종 실패 상태는 이미 확정돼 있으므로 호출부는 best-effort로만 처리한다.
     */
    void recordFailure(long couponId, long memberId, ErrorCode failureReason, LocalDateTime occurredAt);

    /**
     * 메인 스트림 재시도 한도를 넘겨 DLQ로 넘어갔다는 사실만 {@code issue_failure_log}에
     * 남긴다. {@link #recordFailure}와 달리 회원에게 알리지 않는다 — 아직 DLQ 복구를
     * 시도하는 중이라 "발급 실패"라고 단정할 수 없기 때문이다. 같은 발급 건이
     * 나중에 {@link #recordFailure}로 한 번 더(다른 failureReason으로) 기록될 수
     * 있는데, 이건 의도한 중복이다 — 어느 단계까지 실패가 번졌는지 분석할 수 있다.
     */
    void recordRetryEscalation(long couponId, long memberId, ErrorCode reason, LocalDateTime occurredAt);
}
