package com.mocou.consistency;

import java.util.List;

/**
 * 규칙 하나의 검사 결과. {@code verification_rule_result} 한 행과 그에 딸린 상세 행들이 된다.
 *
 * <p>무슨 일이 있었는지 기록만 하고 통과 여부는 판단하지 않는다. 판정은 실행기가 세 갈래로 나눈다. 규칙이 하나라도 실패하면 그 실행은
 * 신뢰할 수 없고(ERROR), 위반이 있으면 FAIL, 전부 검사를 마치고 위반이 없어야 PASS다. 이를 {@code boolean} 하나로 담으면
 * 실패와 위반이 같은 값으로 뭉개진다.
 *
 * <p>{@code violationCount}는 검출된 전체 위반 수이고 {@code violations}는 상한까지 담은 표본이다. 대량 위반이 나오면
 * 둘이 달라진다. 두 값을 한 타입에 같이 두어 적재 계층이 "표본만 저장한다"는 사실을 놓치지 않게 한다.
 *
 * @param checkedCount 이 규칙이 검사한 대상 수
 * @param failureReason 실행에 실패한 사유. 정상 실행이면 {@code null}
 */
public record RuleOutcome(
        VerificationRule rule,
        RuleStatus status,
        long checkedCount,
        long violationCount,
        List<Violation> violations,
        String failureReason) {

    public RuleOutcome {
        if (rule == null || status == null) {
            throw new IllegalArgumentException("규칙과 실행 상태는 필수다");
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
        if (status == RuleStatus.FAILED && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("실패한 규칙은 사유를 남겨야 한다");
        }
    }

    /** 검사를 마쳤고 위반이 없다. */
    public static RuleOutcome passed(VerificationRule rule, long checkedCount) {
        return new RuleOutcome(rule, RuleStatus.CHECKED, checkedCount, 0, List.of(), null);
    }

    /** 검사를 마쳤고 위반을 찾았다. */
    public static RuleOutcome violated(
            VerificationRule rule, long checkedCount, long violationCount, List<Violation> violations) {
        return new RuleOutcome(
                rule, RuleStatus.CHECKED, checkedCount, violationCount, violations, null);
    }

    /**
     * 규칙이 실행에 실패해 판정하지 못했다.
     *
     * <p>실행기는 이 결과를 받아도 나머지 규칙을 계속 돌린다. 규칙 하나가 깨졌다고 앞서 얻은 결과까지 버리지 않는다.
     */
    public static RuleOutcome failed(VerificationRule rule, String reason) {
        return new RuleOutcome(rule, RuleStatus.FAILED, 0, 0, List.of(), reason);
    }

    /** 상한에 걸려 상세를 다 담지 못했는가. 리포트에서 "일부만 표시"를 알리는 데 쓴다. */
    public boolean truncated() {
        return violations.size() < violationCount;
    }
}
