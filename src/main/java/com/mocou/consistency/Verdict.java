package com.mocou.consistency;

/**
 * 검증 실행 한 번의 판정. {@code verification_run.verdict}에 그대로 저장된다.
 *
 * <p>세 갈래인 이유는 "위반이 없다"와 "판정할 수 없다"가 다르기 때문이다. 규칙이 하나라도 죽으면 그 실행의 불일치 0건은 주장으로
 * 성립하지 않는다.
 */
public enum Verdict {

    /** 전 규칙이 끝까지 실행됐고 위반이 없다. */
    PASS,

    /** 위반을 검출했다. */
    FAIL,

    /** 규칙이 실행에 실패해 판정을 내릴 수 없다. 위반이 없다는 뜻이 아니다. */
    ERROR
}
