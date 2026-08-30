package com.mocou.consistency;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * 검증 실행 한 번 동안 테이블 건수 조회를 재사용하는 캐시.
 *
 * <p>규칙들은 서로를 모르는 독립 부품이라 같은 테이블의 전체 건수({@code checked_count}의 분모)를 각자 다시
 * 센다 — 실측으로 {@code coupon_issue} 6회, {@code coupon_issue_history} 3회, 합계 약 4초였다. 검증은
 * {@code CONSISTENT SNAPSHOT} 트랜잭션 하나에서 모든 규칙을 순차 실행하므로 <b>같은 스냅샷 안에서 같은
 * 집계는 몇 번을 세도 같은 값</b>이다. 재사용해도 결과가 달라질 수 없다는 근거가 스냅샷 격리 그 자체다.
 *
 * <p>수명은 실행 1회다. {@link VerificationContext}가 실행마다 새로 만들어지면서 캐시도 함께 새로
 * 만들어지므로, 지난 실행의 값이 다음 실행에 새는 일이 구조적으로 불가능하다. 무효화 로직이 필요 없다.
 *
 * <p>규칙 실행이 단일 커넥션에서 순차이므로 동기화하지 않는다.
 */
public final class CountCache {

    private final Map<String, Long> countsBySql = new HashMap<>();

    /** 같은 SQL이 처음이면 {@code loader}로 세고, 이후에는 적어둔 값을 돌려준다. */
    public long computeIfAbsent(String sql, ToLongFunction<String> loader) {
        Long cached = countsBySql.get(sql);
        if (cached != null) {
            return cached;
        }
        long counted = loader.applyAsLong(sql);
        countsBySql.put(sql, counted);
        return counted;
    }
}
