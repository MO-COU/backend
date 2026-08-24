package com.mocou.consistency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 검증 실행이 끝난 뒤의 결과.
 *
 * <p>{@code issueRunId}와 {@code startedAt}은 담지 않는다. 그 둘은 실행을 시작할 때 이미 행으로 남았고, 이 타입은
 * 끝나고 나서야 알 수 있는 것만 들고 있다.
 *
 * @param snapshotAt 판정 기준 시각. 읽기 트랜잭션이 연 스냅샷의 시점이다
 */
public record VerificationResult(
        LocalDateTime snapshotAt, LocalDateTime finishedAt, List<RuleOutcome> outcomes) {

    public VerificationResult {
        if (snapshotAt == null || finishedAt == null) {
            throw new IllegalArgumentException("기준 시각과 종료 시각은 필수다");
        }
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /**
     * 판정을 계산한다.
     *
     * <p>필드로 들고 있지 않는 이유는 {@code outcomes}와 어긋난 값이 저장될 여지를 없애기 위해서다. 규칙 결과가 곧 판정이므로
     * 매번 그것을 보고 답한다.
     *
     * <p>실패를 위반보다 먼저 본다. 규칙이 죽은 실행은 위반을 못 찾은 것일 뿐이라 {@code FAIL}보다 앞선다.
     */
    public Verdict verdict() {
        if (outcomes.stream().anyMatch(outcome -> outcome.status() == RuleStatus.FAILED)) {
            return Verdict.ERROR;
        }
        if (outcomes.stream().anyMatch(outcome -> outcome.violationCount() > 0)) {
            return Verdict.FAIL;
        }
        return Verdict.PASS;
    }
}
