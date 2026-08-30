package com.mocou.consistency.rule;

import com.mocou.consistency.VerificationContext;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 규칙 구현이 공통으로 쓰는 조회. */
final class RuleQueries {

    private RuleQueries() {}

    /** 파라미터가 없는 집계. */
    static long count(NamedParameterJdbcTemplate jdbcTemplate, String sql) {
        return count(jdbcTemplate, sql, Map.of());
    }

    /**
     * 실행 안에서 한 번만 세는 집계. {@code checked_count}의 분모처럼 규칙들이 같은 SQL을 공유하는 곳에 쓴다.
     *
     * <p>위반 집계에는 쓰지 않는다. 규칙마다 SQL이 달라 재사용될 일이 없고, 검사 본체는 항상 실행된다는
     * 의도를 {@code count}와 이름으로 구분해 남긴다. 재사용이 정합한 근거는 {@link com.mocou.consistency.CountCache}에 있다.
     */
    static long countOnce(
            NamedParameterJdbcTemplate jdbcTemplate, VerificationContext context, String sql) {
        return context.countCache().computeIfAbsent(sql, s -> count(jdbcTemplate, s));
    }

    /**
     * 집계 한 건을 읽는다.
     *
     * <p>{@code queryForObject}는 결과가 없으면 예외를 던지지만 집계 쿼리는 항상 한 행을 돌려주므로 문제되지 않는다. 다만
     * {@code null} 가능성은 남아 있어 0으로 접는다.
     */
    static long count(NamedParameterJdbcTemplate jdbcTemplate, String sql, Map<String, ?> params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }
}
