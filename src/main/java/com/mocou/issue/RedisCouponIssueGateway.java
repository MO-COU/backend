package com.mocou.issue;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 1. Lua Script 를 Classpath에서 로딩
 * 2. Redis에 KEYS와 ARGV를 전달해서 실행
 * 3. Lua 숫자 반환값을 Java enum으로 변환
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisCouponIssueGateway {

    private static final long RESERVATION_APPLIED = 1L;
    private static final long SOLD_OUT = 0L;
    private static final long DUPLICATE_ISSUE = -1L;
    private static final long STOCK_NOT_INITIALIZED = -2L;
    private static final long NOT_OPEN_YET = -3L;
    private static final long ISSUE_CLOSED = -4L;
    private static final long METADATA_NOT_INITIALIZED = -5L;

    private static final long COMPENSATION_APPLIED = 1L;
    private static final long COMPENSATION_NOT_NEEDED = 0L;

    private static final String REPLICATION_UNCONFIRMED =
            "REPLICATION_UNCONFIRMED";

    private static final RedisScript<Long> RESERVE_SCRIPT =
            RedisScript.of(
                    new ClassPathResource(
                            "scripts/redis/reserve-coupon.lua"),
                    Long.class);

    private static final RedisScript<Long> RESERVE_AND_APPEND_EVENT_SCRIPT =
            RedisScript.of(
                    new ClassPathResource(
                            "scripts/redis/reserve-and-append-event.lua"),
                    Long.class);

    private static final RedisScript<Long> COMPENSATE_SCRIPT =
            RedisScript.of(
                    new ClassPathResource(
                            "scripts/redis/compensate-coupon.lua"),
                    Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueReplicationProperties replicationProperties;
    private final CouponIssueReplicationWaiter replicationWaiter;

    public CouponReservationResult reserve(
            long couponId,
            long memberId
    ) {
        validateMemberId(memberId);

        Long result = redisTemplate.execute(
                RESERVE_SCRIPT,
                createReservationKeys(couponId),
                Long.toString(memberId));

        if (result == null) {
            throw new IllegalStateException("Redis reservation script returned null.");
        }

        return toReservationResult(result);
    }

    public CouponReservationResult reserveAndAppendEvent(
            long couponId,
            long memberId,
            UUID eventId
    ) {
        validateMemberId(memberId);

        if (eventId == null) {
            throw new IllegalArgumentException(
                    "eventId는 필수입니다.");
        }

        Long result = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Long execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> stringOperations =
                        (RedisOperations<String, String>) operations;
                return reserveAndWait(
                        stringOperations,
                        couponId,
                        memberId,
                        eventId);
            }
        });

        if (result == null) {
            throw new IllegalStateException("Redis reservation and stream script returned null.");
        }

        return toReservationResult(result);
    }

    private Long reserveAndWait(
            RedisOperations<String, String> operations,
            long couponId,
            long memberId,
            UUID eventId
    ) {
        Long scriptResult = operations.execute(
                RESERVE_AND_APPEND_EVENT_SCRIPT,
                createReservationAndStreamKeys(couponId),
                Long.toString(memberId),
                eventId.toString(),
                Long.toString(couponId));

        if (scriptResult != null
                && scriptResult == RESERVATION_APPLIED
                && replicationProperties.isWaitEnabled()) {
            long acknowledgedReplicas =
                    replicationWaiter.waitForReplication(
                            operations,
                            replicationProperties.getRequiredReplicas(),
                            replicationProperties.getTimeoutMs());
            if (acknowledgedReplicas
                    < replicationProperties.getRequiredReplicas()) {
                recordUnconfirmedReplication(
                        operations,
                        couponId,
                        acknowledgedReplicas);
            }
        }

        return scriptResult;
    }

    private void recordUnconfirmedReplication(
            RedisOperations<String, String> operations,
            long couponId,
            long acknowledgedReplicas
    ) {
        log.warn(
                "Redis 예약 복제 확인 부족 (쿠폰 {}, 필요 Replica {}, 확인 Replica {}, 제한 {}ms)",
                couponId,
                replicationProperties.getRequiredReplicas(),
                acknowledgedReplicas,
                replicationProperties.getTimeoutMs());

        try {
            operations.opsForHash().increment(
                    CouponRedisKey.issueResultCounts(couponId),
                    REPLICATION_UNCONFIRMED,
                    1L);
        } catch (RuntimeException counterFailure) {
            log.error(
                    "Redis 복제 미확인 Counter 기록 실패 (쿠폰 {}, 예외 타입 {})",
                    couponId,
                    counterFailure.getClass().getName());
        }
    }

    public CouponCompensationResult compensate(
            long couponId,
            long memberId
    ) {
        validateMemberId(memberId);

        Long result = redisTemplate.execute(
                COMPENSATE_SCRIPT,
                createCompensationKeys(couponId),
                Long.toString(memberId));

        if (result == null) {
            throw new IllegalStateException("Redis compensation script returned null.");
        }

        return toCompensationResult(result);
    }

    private List<String> createReservationKeys(long couponId) {
        return List.of(
                CouponRedisKey.stock(couponId),
                CouponRedisKey.issuedMembers(couponId),
                CouponRedisKey.metadata(couponId),
                CouponRedisKey.issueSequence(couponId));
    }

    private List<String> createReservationAndStreamKeys(long couponId) {
        return List.of(
                CouponRedisKey.stock(couponId),
                CouponRedisKey.issuedMembers(couponId),
                CouponRedisKey.metadata(couponId),
                CouponRedisKey.issueStream(couponId),
                CouponRedisKey.issueResultCounts(couponId),
                CouponRedisKey.issueSequence(couponId));
    }

    private List<String> createCompensationKeys(long couponId) {
        return List.of(
                CouponRedisKey.stock(couponId),
                CouponRedisKey.issuedMembers(couponId),
                CouponRedisKey.issueResultCounts(couponId));
    }

    private CouponReservationResult toReservationResult(long result) {
        if (result == RESERVATION_APPLIED) {
            return CouponReservationResult.RESERVED;
        }

        if (result == SOLD_OUT) {
            return CouponReservationResult.SOLD_OUT;
        }

        if (result == DUPLICATE_ISSUE) {
            return CouponReservationResult.DUPLICATE_ISSUE;
        }

        if (result == STOCK_NOT_INITIALIZED) {
            return CouponReservationResult.STOCK_NOT_INITIALIZED;
        }

        if (result == NOT_OPEN_YET) {
            return CouponReservationResult.NOT_OPEN_YET;
        }

        if (result == ISSUE_CLOSED) {
            return CouponReservationResult.ISSUE_CLOSED;
        }

        if (result == METADATA_NOT_INITIALIZED) {
            return CouponReservationResult.METADATA_NOT_INITIALIZED;
        }

        throw new IllegalStateException("Unexpected Redis reservation result: " + result);
    }

    private CouponCompensationResult toCompensationResult(long result) {
        if (result == COMPENSATION_APPLIED) {
            return CouponCompensationResult.COMPENSATED;
        }

        if (result == COMPENSATION_NOT_NEEDED) {
            return CouponCompensationResult.NOT_NEEDED;
        }

        if (result == STOCK_NOT_INITIALIZED) {
            return CouponCompensationResult.STOCK_NOT_INITIALIZED;
        }

        throw new IllegalStateException("Unexpected Redis compensation result: " + result);
    }

    private void validateMemberId(long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
    }
}
