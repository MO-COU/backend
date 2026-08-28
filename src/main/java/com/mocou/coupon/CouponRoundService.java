package com.mocou.coupon;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.initialization.CouponRedisInitializationResult;
import com.mocou.issue.initialization.CouponRedisInitializationService;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;
import com.mocou.loadtest.LoadTestRunRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 부하 테스트용 회차를 만든다.
 *
 * <p>DB에만 만들고 끝내지 않는다. 발급 경로는 Redis 재고 키로만 동작하므로, 키가 없으면 그 회차는 발급 요청을 전건 거부한다.
 * <b>회차를 만드는 일과 발급 가능한 상태로 만드는 일은 나눌 수 없다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponRoundService {

    /** 이름을 비우면 이 형식으로 만든다. 기존 회차와 같은 모양이다. */
    private static final String DEFAULT_NAME_FORMAT = "아메리카노 무료 쿠폰 %d회차";

    /** 지난 회차에는 검증 대상인 더미데이터가 들어 있어 지우면 안 된다. */
    private static final String CLOSED_STATUS = "CLOSED";

    private final CouponRoundRepository repository;
    private final CouponRedisInitializationService redisInitializationService;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redisTemplate;
    private final LoadTestRunRepository loadTestRunRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 회차를 만들고 발급 가능한 상태로 둔다.
     *
     * @throws BusinessException 발급 종료 시각이 시작 시각보다 앞서면
     */
    public CouponRoundResponse create(CouponRoundRequest request) {
        long couponId = repository.nextRoundNumber();
        String name = resolveName(request.name(), couponId);
        LocalDateTime closeAt = resolveCloseAt(request.openAt(), request.closeAt());

        rejectIfClosesBeforeOpens(request.openAt(), closeAt);

        transactionTemplate.executeWithoutResult(
                status ->
                        repository.insertRound(
                                couponId, name, request.openAt(), closeAt, request.totalQuantity()));

        initializeRedis(couponId);

        log.info(
                "회차 추가 (쿠폰 {}, 재고 {}, {} ~ {})",
                couponId,
                request.totalQuantity(),
                request.openAt(),
                closeAt);

        return new CouponRoundResponse(
                couponId, name, request.openAt(), closeAt, request.totalQuantity());
    }

    /**
     * 회차와 거기 딸린 모든 기록을 지운다. 되돌릴 수 없다.
     *
     * <p>순서가 정해져 있다. <b>Redis를 먼저 닫고 DB를 지운다.</b> DB를 먼저 지우면 그 사이에도 Redis는
     * 발급을 받고, 그렇게 예약된 건이 없어진 쿠폰을 참조해 <b>컨슈머가 FK 위반으로 죽는다.</b> 리셋에서는
     * "지운 발급이 되살아난다"에 그쳤지만 여기서는 동기화 파이프라인이 멈춘다.
     *
     * <p>{@code create}와 달리 Redis를 다시 세우지 않는다. DB에 쿠폰이 없는데 재고 키를 만들면 아무도
     * 참조하지 않는 유령 키가 남는다.
     *
     * @throws BusinessException 없는 쿠폰이거나, 종료된 회차이거나, 부하 테스트·동기화가 진행 중이면
     */
    public CouponRoundDeleteResult delete(long couponId) {
        rejectIfNotDeletable(couponId);
        rejectIfLoadTestRunning();
        rejectIfSyncInProgress(couponId);

        redisTemplate.delete(CouponRedisKey.allIssueKeys(couponId));

        CouponRoundDeleteResult result =
                Objects.requireNonNull(
                        transactionTemplate.execute(status -> repository.deleteRound(couponId)),
                        "트랜잭션 콜백이 결과를 돌려주지 않았다");

        // 커밋된 뒤에 알린다. 트랜잭션 안에서 알리면 롤백됐을 때 동기화 대상만 바뀐 상태가 남는다.
        eventPublisher.publishEvent(new CouponRoundDeletedEvent(couponId));

        log.info(
                "회차 삭제 (쿠폰 {}, 발급 {}건, 이력 {}건, 검증 {}건)",
                couponId,
                result.deletedIssues(),
                result.deletedHistories(),
                result.deletedVerificationRuns());

        return result;
    }

    /**
     * 지워도 되는 회차인지 본다.
     *
     * <p><b>종료된 회차를 거부한다.</b> 지난 회차 300개가 모두 {@code CLOSED}이고 그 안에 검증 대상인
     * 발급 300만 건이 들어 있다. 리셋은 발급만 지웠지만 삭제는 회차 자체를 없애므로 위험이 더 크다.
     */
    private void rejectIfNotDeletable(long couponId) {
        String status = repository.findStatus(couponId);
        if (status == null) {
            throw new BusinessException(
                    ErrorCode.COUPON_NOT_FOUND, "쿠폰 %d를 찾을 수 없습니다".formatted(couponId));
        }
        if (CLOSED_STATUS.equals(status)) {
            throw new BusinessException(
                    ErrorCode.COUPON_ROUND_NOT_DELETABLE,
                    "쿠폰 %d는 종료된 회차다. 검증 대상인 더미데이터가 들어 있어 지우면 복구할 방법이 재적재뿐이다"
                            .formatted(couponId));
        }
    }

    /**
     * 부하 테스트가 도는 중에는 어느 회차도 지우지 못하게 한다.
     *
     * <p>아래 {@link #rejectIfSyncInProgress}는 <b>지우려는 쿠폰</b>의 상태만 보므로, 302에서
     * 테스트하는 중에 301을 지우는 것은 그냥 통과한다. 그러면 삭제 뒤 활성 동기화 대상을 다시 정하는
     * 과정에서 진행 중인 테스트의 대상이 엉뚱한 쿠폰으로 밀려날 수 있다.
     *
     * <p>{@code LoadTestExecutionService.start()}가 이미 같은 검사로 "동시에 두 테스트를 못 돌린다"를
     * 강제한다. 같은 것을 여기서도 써서 "테스트 중에는 안 지운다"를 규약이 아니라 코드로 만든다.
     */
    private void rejectIfLoadTestRunning() {
        if (loadTestRunRepository.existsRunning()) {
            throw new BusinessException(
                    ErrorCode.COUPON_ROUND_NOT_DELETABLE, "부하 테스트가 진행 중이다. 끝난 뒤 삭제해야 한다");
        }
    }

    /**
     * 컨슈머가 읽어갔지만 아직 끝내지 못한 발급이 있으면 거부한다.
     *
     * <p>스트림에 남아 있기만 한 이벤트는 보지 않는다. 어차피 키째로 지우기 때문이다. 막아야 하는 것은
     * <b>이미 읽혀서 우리가 지울 수 없는 것</b>이다. 그대로 두면 삭제가 끝난 뒤 컨슈머가 DB에 넣으려다
     * 없는 쿠폰을 참조해 FK 위반으로 죽는다.
     *
     * <p>DLQ에서 복구를 기다리는 건도 같다.
     */
    private void rejectIfSyncInProgress(long couponId) {
        long unacknowledged =
                unacknowledgedCount(
                        CouponRedisKey.issueStream(couponId),
                        RedisCouponIssueSyncGateway.GROUP_NAME);
        if (unacknowledged > 0) {
            throw new BusinessException(
                    ErrorCode.LOAD_TEST_SYNC_IN_PROGRESS,
                    "발급 이벤트 %d건이 컨슈머에서 처리 중이다. 끝난 뒤 다시 요청해야 한다".formatted(unacknowledged));
        }

        long dlqUnacknowledged =
                unacknowledgedCount(
                        CouponRedisKey.issueDlqStream(couponId),
                        RedisCouponIssueSyncGateway.DLQ_GROUP_NAME);
        if (dlqUnacknowledged > 0) {
            throw new BusinessException(
                    ErrorCode.LOAD_TEST_SYNC_IN_PROGRESS,
                    "DLQ에서 재시도 중인 발급 이벤트 %d건이 있다. 끝난 뒤 다시 요청해야 한다"
                            .formatted(dlqUnacknowledged));
        }
    }

    /** 컨슈머 그룹이 아직 없으면 미확인 건도 없다. 부하 테스트를 한 번도 돌리지 않은 회차다. */
    private long unacknowledgedCount(String streamKey, String groupName) {
        try {
            var pending = redisTemplate.opsForStream().pending(streamKey, groupName);
            return pending == null ? 0 : pending.getTotalPendingMessages();
        } catch (DataAccessException groupNotFound) {
            return 0;
        }
    }

    private String resolveName(String requested, long couponId) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_NAME_FORMAT.formatted(couponId);
        }
        return requested;
    }

    /**
     * 발급 종료 시각을 정한다. 비어 있으면 <b>시작 당일 자정 직전</b>이다.
     *
     * <p>{@code openAt}에 하루를 더하는 것이 아니다. 기존 회차 300개가 모두 "당일 10시에 열어 그날 안에 닫는" 모양이라
     * 같은 모형을 따른다.
     */
    private LocalDateTime resolveCloseAt(LocalDateTime openAt, LocalDateTime requested) {
        if (requested != null) {
            return requested;
        }
        return openAt.toLocalDate().atTime(LocalTime.of(23, 59, 59));
    }

    /**
     * 종료가 시작보다 앞서면 거부한다.
     *
     * <p>Redis 초기화 스크립트도 같은 검사를 하지만 거기까지 가면 <b>DB에는 이미 회차가 들어간 뒤</b>다. 회차만 만들어지고
     * Redis가 비어 "열려 있는데 아무도 받을 수 없는" 상태가 된다.
     *
     * <p>{@code closeAt}은 기본값일 수도 있어 요청 DTO의 애노테이션으로는 검사할 수 없다. 기본값을 채운 뒤라야 비교가
     * 성립한다.
     */
    private void rejectIfClosesBeforeOpens(LocalDateTime openAt, LocalDateTime closeAt) {
        if (!closeAt.isAfter(openAt)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "발급 종료 시각(%s)은 시작 시각(%s)보다 뒤여야 합니다".formatted(closeAt, openAt));
        }
    }

    /**
     * 재고와 발급 시각을 Redis에 세운다.
     *
     * <p>새로 만든 회차라 키가 없어야 하므로 {@code INITIALIZED}가 나와야 한다. {@code ALREADY_INITIALIZED}가
     * 나왔다면 같은 번호의 키가 남아 있다는 뜻이며, 이전 회차의 잔재이므로 그대로 두면 재고가 어긋난다.
     *
     * <p>여기서 실패해도 DB의 회차는 롤백하지 않는다. 트랜잭션을 Redis까지 늘리면 DB 커넥션을 잡은 채 네트워크를 기다리게 되고,
     * Redis는 어차피 롤백되지 않아 원자성이 반쪽이 된다. 회차가 남아 있으면 초기화만 다시 하면 되므로 <b>발급이 막힌 채 멈추는
     * 쪽</b>을 택한다.
     */
    private void initializeRedis(long couponId) {
        CouponRedisInitializationResult result = redisInitializationService.initialize(couponId);
        if (result != CouponRedisInitializationResult.INITIALIZED) {
            throw new IllegalStateException(
                    "회차 추가 후 Redis 초기화가 예상과 다르다. couponId=%d, result=%s"
                            .formatted(couponId, Objects.toString(result)));
        }
    }
}
