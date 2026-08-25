package com.mocou.consistency;

/**
 * 검증 실행을 시작했다는 응답.
 *
 * <p>결과는 담지 않는다. 검증이 1~2분 걸려 응답을 붙잡고 기다릴 수 없다. 진행 상황과 결과는
 * {@code verification_run}을 조회해 확인한다. {@code finished_at}이 {@code NULL}이면 아직 돌고 있다.
 */
public record VerificationStartResponse(long runId, String message) {

    static VerificationStartResponse started(long runId) {
        return new VerificationStartResponse(runId, "정합성 검증을 시작했습니다. 완료까지 몇 분이 걸릴 수 있습니다.");
    }
}
