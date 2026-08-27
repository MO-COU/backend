package com.mocou.loadtest;

import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.issue.initialization.CouponRedisInitializationResult;
import com.mocou.issue.initialization.CouponRedisInitializationService;
import com.mocou.issue.sync.RedisCouponIssueSyncGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부하 테스트가 남긴 것을 지우고 시연 회차를 발급 직전 상태로 되돌린다.
 *
 * <p>순서가 정해져 있다. <b>Redis를 먼저 닫고, DB를 치우고, 다시 연다.</b> DB를 먼저 지우면 그동안 Redis는 여전히
 * 발급을 받으므로, 그 사이 발급된 건이 리셋이 끝난 뒤에도 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestResetService {

    /** 지난 회차에는 검증 대상인 더미데이터가 들어 있어 되돌리면 안 된다. */
    private static final String CLOSED_STATUS = "CLOSED";

    private final LoadTestResetRepository repository;
    private final CouponRedisInitializationService redisInitializationService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RedisCouponIssueSyncGateway syncGateway;

    /**
     * 되돌린다.
     *
     * @throws BusinessException 대상을 특정할 수 없거나 아직 DB로 반영되지 않은 발급이 남아 있으면
     */
    public LoadTestResetResult reset(long couponId) {
        rejectIfNotResettable(couponId);
        rejectIfSyncInProgress(couponId);

        deleteRedisKeys(couponId);
        LoadTestResetResult result = deleteDatabaseRecords(couponId);
        initializeRedis(couponId);

        log.info(
                "부하 테스트 리셋 완료 (쿠폰 {}, 발급 {}건, 이력 {}건, 재고 {}로 복구)",
                couponId,
                result.deletedIssues(),
                result.deletedHistories(),
                result.restoredStock());

        return result;
    }

    /**
     * 되돌려도 되는 회차인지 본다.
     *
     * <p>처음에는 대상을 받지 않고 {@code OPEN}인 쿠폰 하나를 서버가 찾았다. 오타 한 글자에 지난 회차를 지목하면 검증 대상인
     * 발급 300만 건이 사라지기 때문이었다. <b>회차를 직접 만들 수 있게 되면서 {@code OPEN}이 여럿이 되어</b> 그 방식으로는
     * 대상을 특정할 수 없다.
     *
     * <p>대신 <b>종료된 회차를 거부한다.</b> 지난 회차 300개가 모두 {@code CLOSED}라 지정해도 막히므로, 파라미터를
     * 받아도 같은 사고가 나지 않는다.
     */
    private void rejectIfNotResettable(long couponId) {
        String status = repository.findStatus(couponId);
        if (status == null) {
            throw new BusinessException(
                    ErrorCode.COUPON_NOT_FOUND, "쿠폰 %d를 찾을 수 없습니다".formatted(couponId));
        }
        if (CLOSED_STATUS.equals(status)) {
            throw new BusinessException(
                    ErrorCode.LOAD_TEST_TARGET_CLOSED,
                    "쿠폰 %d는 종료된 회차다. 되돌리면 복구할 방법이 재적재뿐이다".formatted(couponId));
        }
    }

    /**
     * 컨슈머가 읽어갔지만 아직 끝내지 못한 발급이 있으면 거부한다.
     *
     * <p>스트림에 남아 있는 이벤트는 확인하지 않는다. 어차피 아래에서 키째로 지우기 때문이다. 막아야 하는 것은 <b>이미 읽혀서 우리가
     * 지울 수 없는 것</b>이다. 그대로 두면 리셋이 끝난 뒤 컨슈머가 DB에 넣어, 방금 지운 발급이 되살아난다.
     */
    private void rejectIfSyncInProgress(long couponId) {
        long unacknowledged = unacknowledgedCount(CouponRedisKey.issueStream(couponId));
        if (unacknowledged > 0) {
            throw new BusinessException(
                    ErrorCode.LOAD_TEST_SYNC_IN_PROGRESS,
                    "발급 이벤트 %d건이 컨슈머에서 처리 중이다. 끝난 뒤 다시 요청해야 한다".formatted(unacknowledged));
        }
    }

    /** 컨슈머 그룹이 아직 없으면 미확인 건도 없다. 부하 테스트를 한 번도 돌리지 않은 경우다. */
    private long unacknowledgedCount(String streamKey) {
        try {
            var pending =
                    redisTemplate
                            .opsForStream()
                            .pending(streamKey, RedisCouponIssueSyncGateway.GROUP_NAME);
            return pending == null ? 0 : pending.getTotalPendingMessages();
        } catch (DataAccessException groupNotFound) {
            return 0;
        }
    }

    /**
     * 발급에 쓰이는 키를 모두 지운다.
     *
     * <p>재고만 되돌리면 {@code issued-members}에 남은 회원이 다음 부하 테스트에서 중복으로 걸러진다.
     * 결과 카운터를 남겨두면 이전 회차의 예약 성공·품절 결과가 다음 회차에 누적된다.
     * 발급 순번을 남겨두면 다음 회차가 1번부터 시작하지 않는다.
     * 스트림을 지우면 컨슈머 그룹도 함께 사라지므로 초기화 단계에서 다시 생성한다.
     */
    private void deleteRedisKeys(long couponId) {
        redisTemplate.delete(
                Set.of(
                        CouponRedisKey.stock(couponId),
                        CouponRedisKey.metadata(couponId),
                        CouponRedisKey.issuedMembers(couponId),
                        CouponRedisKey.issueStream(couponId),
                        CouponRedisKey.issueResultCounts(couponId),
                        CouponRedisKey.issueSequence(couponId)));
    }

    /**
     * DB에서 지우고 재고를 되돌린다. <b>여기만</b> 한 트랜잭션으로 묶는다.
     *
     * <p>{@code @Transactional}을 메서드 전체에 걸지 않는 이유는 Redis 작업이 트랜잭션 안에 들어가면 안 되기 때문이다.
     * Redis는 롤백되지 않아 원자성이 반쪽이 되고, DB 커넥션을 잡은 채 네트워크를 기다리게 된다. 애노테이션은 메서드 단위라 일부만
     * 감쌀 수 없어 {@link TransactionTemplate}을 쓴다.
     *
     * <p>지우는 순서는 FK가 정한다. 이력이 발급을 참조하므로 이력을 먼저 지우지 않으면 {@code ERROR 1451}로 막힌다.
     */
    private LoadTestResetResult deleteDatabaseRecords(long couponId) {
        LoadTestResetResult result =
                transactionTemplate.execute(
                        status -> {
                            int histories = repository.deleteHistories(couponId);
                            int issues = repository.deleteIssues(couponId);
                            int failureLogs = repository.deleteFailureLogs(couponId);
                            int notifications = repository.deleteNotifications(couponId);
                            int verificationRuns = repository.deleteAllVerificationRecords();
                            int restoredStock = repository.restoreStock(couponId);

                            return new LoadTestResetResult(
                                    couponId,
                                    issues,
                                    histories,
                                    failureLogs,
                                    notifications,
                                    verificationRuns,
                                    restoredStock);
                        });
        return Objects.requireNonNull(result, "트랜잭션 콜백이 결과를 돌려주지 않았다");
    }

    /**
     * 재고와 발급 시간을 DB 값으로 다시 세운다.
     *
     * <p>초기화 스크립트는 키가 이미 있으면 덮어쓰지 않으므로 위에서 먼저 지워야 한다. {@code datagen}이 마지막에 부르는 것과
     * 같은 경로라 값이 어긋날 여지가 없다.
     *
     * <p>{@code datagen}은 {@code ALREADY_INITIALIZED}를 정상으로 보지만 여기서는 아니다. 바로 앞에서 키를
     * 지웠으므로 새로 세워졌어야 한다. 그 값이 나왔다면 삭제가 되지 않은 것이다.
     *
     * <p>재고와 함께 컨슈머 그룹도 되살린다. 이유는 아래에 적었다.
     */
    private void initializeRedis(long couponId) {
        CouponRedisInitializationResult result = redisInitializationService.initialize(couponId);
        if (result != CouponRedisInitializationResult.INITIALIZED) {
            throw new IllegalStateException(
                    "리셋 후 Redis 초기화가 예상과 다르다. couponId=%d, result=%s".formatted(couponId, result));
        }

        restoreConsumerGroup(couponId);
    }

    /**
     * 발급 이벤트를 읽는 컨슈머 그룹을 되살린다.
     *
     * <p>위에서 {@code issue-stream} 키를 지우면 <b>컨슈머 그룹도 함께 사라진다.</b> 그런데 컨슈머는 그룹을 만들었다는
     * 사실을 자기 메모리에 들고 있어 다시 만들지 않는다.
     *
     * <pre>
     * 리셋      issue-stream 삭제 → 그룹도 사라짐
     * 부하 테스트 스트림에 이벤트가 쌓임
     * 컨슈머     "이미 만들어놨다" → 생성 건너뜀
     * 결과      XREADGROUP이 NOGROUP만 내고 한 건도 DB로 넘어가지 않는다
     * </pre>
     *
     * <p>실제로 이 상태에서 발급 10,000건이 스트림에 쌓인 채 멈췄고, 앱을 재시작해서야 풀렸다. <b>이 호출을 지우면 그 상황이
     * 그대로 재현된다.</b>
     *
     * <p>이미 있으면 아무 일도 하지 않는 멱등 연산이라 리셋을 두 번 불러도 안전하다.
     */
    private void restoreConsumerGroup(long couponId) {
        syncGateway.ensureConsumerGroup(couponId);
    }
}
