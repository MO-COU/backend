package com.mocou.consistency;

/**
 * 정합성 검증 규칙의 이름. {@code verification_rule_result.rule_name}에 그대로 저장된다.
 *
 * <p>문자열 리터럴을 쓰면 규칙 이름이 여러 클래스에 흩어지고 오타가 나도 컴파일이 통과한다. 규칙을 추가할 때 확인할 곳을 이 한 곳으로 모은다.
 *
 * <p>각 규칙의 판정식은 {@code docs/b1/consistency-rules.md}에 있다.
 */
public enum VerificationRule {

    /** 한 회원이 같은 쿠폰을 2장 이상 받았는가. */
    DUPLICATE_ISSUE,

    /** 쿠폰별 발급 건수가 총 재고를 넘었는가. */
    OVER_ISSUE,

    /** 총재고가 발급 건수와 잔여 재고의 합과 어긋나는가. */
    STOCK_MISMATCH,

    /** 존재하지 않는 회원·쿠폰·발급 건을 가리키는 행이 있는가. */
    ORPHAN_REFERENCE,

    /** 발급 한 행 안에서 상태와 시각이 서로 모순되는가. */
    STATE_TIMESTAMP_MISMATCH,

    /** 상태 이력 체인이 현재 상태와 어긋나거나 끊겼는가. */
    HISTORY_MISMATCH,

    /** 위반을 일부러 주입했을 때 규칙이 실제로 검출하는가. */
    TOOL_RELIABILITY,

    /** Redis 발급 집합·재고가 DB 이력과 어긋나는가. 동기화가 끝나지 않았으면 판정하지 않는다. */
    REDIS_DB_MISMATCH,

    /**
     * Redis가 확정한 예약 순번과 차감 후 잔여 재고가 서로, 그리고 총재고와 맞는가.
     *
     * <p>발급 순서를 정하는 권위는 Redis Lua의 원자적 실행뿐이라 DB 안에는 대조할 순서가 없다. 대신 같은 실행이 만든 두 개의
     * 독립 카운터({@code INCR} 순번, {@code DECR} 재고)를 DB가 원래 갖고 있던 총재고와 맞춰본다.
     */
    ISSUE_SEQUENCE_MISMATCH
}
