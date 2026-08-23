package com.mocou.consistency;

import java.util.List;

/**
 * 규칙 하나의 검사 결과. {@code verification_rule_result} 한 행과 그에 딸린 상세 행들이 된다.
 *
 * <p>{@code violationCount}는 검출된 전체 위반 수이고 {@code violations}는 상한까지 담은 표본이다. 대량 위반이 나오면
 * 둘이 달라진다. 두 값을 한 타입에 같이 두어 적재 계층이 "표본만 저장한다"는 사실을 놓치지 않게 한다.
 *
 * @param checkedCount 이 규칙이 검사한 대상 수. 0이면 통과가 아니라 검사하지 못했다는 신호다
 */
public record RuleOutcome(
        VerificationRule rule, long checkedCount, long violationCount, List<Violation> violations) {

    public RuleOutcome {
        if (rule == null) {
            throw new IllegalArgumentException("규칙은 필수다");
        }
        if (checkedCount < 0 || violationCount < 0) {
            throw new IllegalArgumentException(
                    "검사 건수(%d)와 위반 건수(%d)는 음수일 수 없다".formatted(checkedCount, violationCount));
        }
        violations = violations == null ? List.of() : List.copyOf(violations);
        if (violations.size() > violationCount) {
            throw new IllegalArgumentException(
                    "표본(%d건)이 전체 위반 수(%d건)보다 많다. 집계와 상세가 다른 기준으로 만들어졌다는 뜻이다"
                            .formatted(violations.size(), violationCount));
        }
    }

    /** 위반이 없는 결과. */
    public static RuleOutcome passed(VerificationRule rule, long checkedCount) {
        return new RuleOutcome(rule, checkedCount, 0, List.of());
    }

    public boolean passed() {
        return violationCount == 0;
    }

    /** 상한에 걸려 상세를 다 담지 못했는가. 리포트에서 "일부만 표시"를 알리는 데 쓴다. */
    public boolean truncated() {
        return violations.size() < violationCount;
    }
}
