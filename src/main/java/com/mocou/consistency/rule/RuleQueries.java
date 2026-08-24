package com.mocou.consistency.rule;

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
