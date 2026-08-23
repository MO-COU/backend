package com.mocou.consistency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정합성 검증 규칙 하나의 계약.
 *
 * <p>구현체는 판정만 한다. PASS/FAIL 결정과 결과 적재는 실행기가 맡는다. 검출한 위반을 고치지 않는 것도 규칙 전체에 걸린 원칙이다.
 *
 * <p>규칙을 타입으로 쪼갠 이유는 세 가지다. {@code REDIS_DB_MISMATCH}를 스펙 확정 후 클래스 하나 추가로 붙일 수 있고, 위반
 * 주입 시험이 규칙마다 다른 우회 방법을 짝지어야 하며, 비용이 큰 규칙만 골라 건너뛰려면 실행 단위가 규칙이어야 한다.
 */
public interface ConsistencyRule {

    VerificationRule rule();

    /**
     * 규칙을 실행하고 결과를 돌려준다.
     *
     * <p>{@code jdbcTemplate}을 파라미터로 받는 이유는 실행기가 연 읽기 전용 트랜잭션 안에서 돌아야 하기 때문이다. 구현체가
     * 커넥션을 따로 잡으면 그 트랜잭션의 스냅샷 밖에서 데이터를 읽게 되고, 규칙마다 다른 시점을 보게 된다.
     *
     * <p>위반 상세를 조회할 때는 {@code ORDER BY}를 반드시 함께 건다. 정렬 없이 {@code LIMIT}만 걸면 표본이 실행마다
     * 달라져 재현성이 깨진다.
     */
    RuleOutcome check(JdbcTemplate jdbcTemplate, VerificationContext context);
}
