package com.mocou.coupon;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.initialization.CouponRedisInitializationResult;
import com.mocou.issue.initialization.CouponRedisInitializationService;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final CouponRoundRepository repository;
    private final CouponRedisInitializationService redisInitializationService;
    private final TransactionTemplate transactionTemplate;

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
