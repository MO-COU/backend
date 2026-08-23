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

    /**
     * Redis 발급 집합과 DB 이력의 양방향 차집합.
     *
     * <p>A팀 키 스펙이 확정되어야 판정식을 정할 수 있어 아직 구현이 없다. 상수만 두어 보류 상태가 코드에도 드러나게 한다.
     */
    REDIS_DB_MISMATCH
}
