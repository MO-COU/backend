package com.mocou.consistency.rule;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RedisSyncBaseline;
import com.mocou.consistency.RedisSyncBaselineReader;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.consistency.Violation;
import com.mocou.consistency.ViolationTarget;
import com.mocou.issue.CouponRedisKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 발급 결과와 DB 이력이 일치하는지 검사한다.
 *
 * <p>발급은 Redis에서 확정되고 DB 반영은 Stream 컨슈머가 뒤따른다. 그래서 <b>발급이 진행 중인 동안에는 판정 자체가 성립하지
 * 않는다.</b> 이유는 두 가지다.
 *
 * <p>첫째, 두 저장소를 같은 시점으로 맞출 수 없다. DB 규칙들은 읽기 트랜잭션의 스냅샷으로 시점을 고정하지만 Redis는 그 밖이라 읽는
 * 순간의 상태를 본다. 그 사이 발급된 건이 Redis에만 있는 것으로 잡히는데, 실제 유실인지 시점 어긋남인지 구분할 수 없다.
 *
 * <p>둘째, 비동기라 지연이 정상이다. 발급 중에는 늘 차이가 있으므로 규칙이 정상 지연과 실제 유실을 가릴 수 없다.
 *
 * <p>동기화가 끝나야 "Redis와 DB는 같아야 한다"는 단정이 성립한다. 그 상태인지는 사람에게 묻지 않고 스트림에서 직접 확인한다.
 * 끝나지 않았으면 위반 0건이 아니라 <b>판정 불가</b>로 남긴다.
 *
 * <p>확인하는 시점은 둘이다. 스냅샷을 열기 전에 동기화가 끝나 있어야 그때까지의 발급이 모두 스냅샷에 있고, Redis를 다 읽을
 * 때까지 발급 순번이 그대로여야 읽는 동안 새 발급이 없었다. 이 규칙이 돌 때만 확인하면 스냅샷을 연 뒤에 끝난 동기화를 "끝났다"로
 * 읽어, 스냅샷에 없는 정상 발급을 유실로 판정한다.
 */
@Component
@RequiredArgsConstructor
class RedisDbMismatchRule implements ConsistencyRule {

    /**
     * 검사 대상 쿠폰. Redis 키는 발급을 여는 쿠폰에만 만들어지므로 DB에서 그 목록을 가져온다.
     *
     * <p>과거 회차는 Redis에 키가 없다. 그것까지 검사하면 "키가 없다"가 전부 위반으로 잡힌다.
     */
    private static final String OPEN_COUPON_SQL =
            "SELECT coupon_id FROM coupon WHERE status = 'OPEN' ORDER BY coupon_id";

    private static final String ISSUED_MEMBER_SQL =
            "SELECT member_id FROM coupon_issue WHERE coupon_id = :couponId";

    private static final String REMAINING_QUANTITY_SQL =
            "SELECT remaining_quantity FROM coupon_stock WHERE coupon_id = :couponId";

    private final StringRedisTemplate redisTemplate;
    private final RedisSyncBaselineReader syncReader;

    @Override
    public VerificationRule rule() {
        return VerificationRule.REDIS_DB_MISMATCH;
    }

    @Override
    public RuleOutcome check(NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context) {
        List<Long> couponIds = jdbcTemplate.queryForList(OPEN_COUPON_SQL, Map.of(), Long.class);
        if (couponIds.isEmpty()) {
            return RuleOutcome.passed(rule(), 0);
        }

        long checkedCount = 0;
        long violationCount = 0;
        List<Violation> violations = new ArrayList<>();

        RedisSyncBaseline baseline = context.redisSyncBaseline();
        for (long couponId : couponIds) {
            String notReady = baseline.notReadyReason(couponId);
            if (notReady != null) {
                return RuleOutcome.failed(rule(), notReady);
            }
            String pending = syncReader.pendingDescription(couponId);
            if (pending != null) {
                return RuleOutcome.failed(rule(), pending);
            }

            Set<Long> redisMembers = redisIssuedMembers(couponId);
            Set<Long> dbMembers =
                    new HashSet<>(
                            jdbcTemplate.queryForList(
                                    ISSUED_MEMBER_SQL, Map.of("couponId", couponId), Long.class));

            // 회원 대조 건수에 재고 대조 1건을 더한다. 발급 회원이 하나도 없어도 재고는 대조하므로,
            // 빼면 재고만 어긋났을 때 "검사 0건인데 위반 1건"이라는 앞뒤가 안 맞는 결과가 남는다.
            checkedCount += redisMembers.size() + dbMembers.size() + 1;

            violationCount +=
                    collectMemberGaps(couponId, redisMembers, dbMembers, context, violations);
            violationCount += collectStockGap(jdbcTemplate, couponId, context, violations);

            String sequenceAtStart = baseline.issueSequence(couponId);
            String sequenceNow = syncReader.issueSequence(couponId);
            if (!Objects.equals(sequenceAtStart, sequenceNow)) {
                return RuleOutcome.failed(
                        rule(),
                        "쿠폰 %d의 발급이 검증 도중 진행됐다 (순번 %s → %s). 발급이 멈춘 뒤 다시 검증해야 한다"
                                .formatted(couponId, orNone(sequenceAtStart), orNone(sequenceNow)));
            }
        }

        if (violationCount == 0) {
            return RuleOutcome.passed(rule(), checkedCount);
        }
        return RuleOutcome.violated(rule(), checkedCount, violationCount, violations);
    }

    private Set<Long> redisIssuedMembers(long couponId) {
        Set<String> raw =
                redisTemplate
                        .opsForZSet()
                        .range(CouponRedisKey.issuedMembers(couponId), 0, -1);
        Set<Long> members = new HashSet<>();
        if (raw != null) {
            raw.forEach(value -> members.add(Long.parseLong(value)));
        }
        return members;
    }

    /**
     * 양방향 차집합을 낸다.
     *
     * <p>동기화가 끝난 상태이므로 두 방향 모두 위반이다. Redis에만 있으면 DB 적재가 유실된 것이고, DB에만 있으면 발급하지 않은
     * 회원의 이력이 있다는 뜻이다.
     */
    private long collectMemberGaps(
            long couponId,
            Set<Long> redisMembers,
            Set<Long> dbMembers,
            VerificationContext context,
            List<Violation> violations) {
        Set<Long> onlyInRedis = new TreeSet<>(redisMembers);
        onlyInRedis.removeAll(dbMembers);
        Set<Long> onlyInDb = new TreeSet<>(dbMembers);
        onlyInDb.removeAll(redisMembers);

        addMemberViolations(onlyInRedis, couponId, "ISSUED_ONLY_IN_REDIS", context, violations);
        addMemberViolations(onlyInDb, couponId, "ISSUED_ONLY_IN_DB", context, violations);
        return (long) onlyInRedis.size() + onlyInDb.size();
    }

    /** 정렬된 집합을 순서대로 담는다. 상한에 걸려도 매번 같은 표본이 남아야 재현성이 유지된다. */
    private void addMemberViolations(
            Set<Long> memberIds,
            long couponId,
            String code,
            VerificationContext context,
            List<Violation> violations) {
        for (long memberId : memberIds) {
            if (violations.size() >= context.violationLimit()) {
                return;
            }
            violations.add(
                    new Violation(
                            ViolationTarget.COUPON_MEMBER_PAIR,
                            couponId,
                            memberId,
                            "%s: 쿠폰 %d 회원 %d".formatted(code, couponId, memberId)));
        }
    }

    private static String orNone(String value) {
        return value == null ? "없음" : value;
    }

    /** Redis 재고와 DB 잔여 재고를 맞춰본다. 키가 없으면 초기화 전이거나 유실이므로 위반이다. */
    private long collectStockGap(
            NamedParameterJdbcTemplate jdbcTemplate,
            long couponId,
            VerificationContext context,
            List<Violation> violations) {
        String redisStock = redisTemplate.opsForValue().get(CouponRedisKey.stock(couponId));
        Integer dbRemaining =
                jdbcTemplate.queryForObject(
                        REMAINING_QUANTITY_SQL, Map.of("couponId", couponId), Integer.class);

        if (redisStock != null && dbRemaining != null && Long.parseLong(redisStock) == dbRemaining) {
            return 0;
        }
        if (violations.size() < context.violationLimit()) {
            violations.add(
                    Violation.of(
                            ViolationTarget.COUPON,
                            couponId,
                            "STOCK_COUNT_MISMATCH: Redis %s, DB %s"
                                    .formatted(
                                            redisStock == null ? "없음" : redisStock,
                                            dbRemaining == null ? "없음" : dbRemaining)));
        }
        return 1;
    }
}
