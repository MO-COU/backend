package com.mocou.issue.sync;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * "지금 발급 동기화 파이프라인이 처리해야 할 단 하나의 couponId"를 들고 있는 공용 상태.
 *
 * <p>{@link CouponIssueSyncConsumer}(메인 스트림)와 {@link CouponIssueDlqRecoveryConsumer}
 * (DLQ 복구)는 항상 같은 쿠폰을 봐야 한다 — "이전 쿠폰은 DLQ까지 전부 끝나야 다음 쿠폰이
 * 시작된다"는 운영 정책상 두 컨슈머가 서로 다른 쿠폰을 보는 상황 자체가 없기 때문이다.
 * 그래서 각자 따로 추적하지 않고 여기 하나로 모은다.
 */
@Component
@ConditionalOnProperty(prefix = "mocou.issue.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class ActiveCouponIdHolder {

    private final CouponIssueSyncRepository repository;

    // 앱 기동 시 DB 조회로 한 번 정해지고, 이후로는 CouponSyncTargetChangedEvent를
    // 받을 때만 바뀐다. 여러 스레드(각 컨슈머의 @Scheduled 스레드, 이벤트를 발행하는
    // API 요청 스레드)가 읽고 쓰므로 volatile로 가시성을 보장한다.
    private volatile Long activeCouponId;

    @PostConstruct
    void init() {
        List<Long> openCouponIds = repository.findOpenCouponIds();
        activeCouponId = openCouponIds.isEmpty() ? null : openCouponIds.getFirst();
    }

    /**
     * 발급을 실제로 시작시키는 쪽(예: 부하 테스트 시작 API)이 발행하는 이벤트를 받아
     * 즉시 전환한다. DB를 다시 조회하지 않고 이벤트가 담아온 couponId를 그대로 신뢰한다.
     */
    @EventListener
    void onSyncTargetChanged(CouponSyncTargetChangedEvent event) {
        activeCouponId = event.couponId();
    }

    /**
     * 회차가 지워지면 대상을 다시 정한다.
     *
     * <p>지워진 쿠폰은 이미 {@code OPEN} 목록에 없으므로 {@link #init}이 알아서 다른 쿠폰(없으면
     * {@code null})을 고른다. 그래서 삭제하는 쪽이 "다음 대상이 누구인지"를 알 필요가 없다.
     *
     * <p>지운 쿠폰을 계속 가리키면 컨슈머가 {@code NOGROUP}을 감지해 그룹을 다시 만들면서 방금
     * 지운 스트림 키가 되살아난다.
     *
     * <p>진행 중인 부하 테스트의 대상이 여기서 밀려날 걱정은 없다. 삭제 자체가 부하 테스트가 도는
     * 동안에는 거부되기 때문이다({@code CouponRoundService.rejectIfLoadTestRunning}).
     */
    @EventListener
    void onCouponRoundDeleted(CouponRoundDeletedEvent event) {
        init();
    }

    Long get() {
        return activeCouponId;
    }
}
