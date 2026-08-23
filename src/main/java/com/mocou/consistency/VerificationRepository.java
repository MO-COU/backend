package com.mocou.consistency;

import java.time.LocalDateTime;

/**
 * 검증 실행 기록을 남긴다.
 *
 * <p>실행을 시작할 때 행을 먼저 만들고 끝난 뒤 채운다. 검증이 1~2분 걸려 그동안 "돌고 있다"는 사실이 DB에 보여야 대시보드가 진행
 * 상황을 알 수 있고 중복 실행도 막을 수 있다.
 */
public interface VerificationRepository {

    /**
     * 실행을 시작했다고 기록한다.
     *
     * <p>{@code snapshot_at}과 {@code verdict}는 아직 모르므로 {@code NULL}로 남는다. 스냅샷 시각은 검증
     * 트랜잭션을 열어야 정해지고 판정은 규칙을 다 돌려야 나온다.
     *
     * @param issueRunId 부하 테스트 직후 그 실행을 검증하면 {@code coupon_issue_run.run_id}, 더미데이터 전체를
     *     보는 검증이면 {@code null}
     * @return 생성된 {@code verification_run.run_id}
     */
    /**
     * 아직 끝나지 않은 실행이 있는지 본다.
     *
     * <p>{@code startedAt}이 기준보다 오래된 행은 세지 않는다. 실행 중 애플리케이션이 죽으면
     * {@code finished_at}이 영원히 {@code NULL}로 남는데, 그것까지 "진행 중"으로 보면 그다음부터 검증을 아예 못
     * 하게 된다.
     *
     * @param startedAfter 이 시각 이후에 시작한 것만 살아 있다고 본다
     */
    boolean hasRunningSince(LocalDateTime startedAfter);

    long startRun(Long issueRunId, LocalDateTime startedAt);

    /**
     * 끝난 실행을 채운다. 규칙별 결과와 위반 상세도 함께 적재한다.
     *
     * <p>읽기 트랜잭션이 끝난 뒤 호출한다. 읽기 전용 트랜잭션 안에서는 INSERT가 거부되고, 검증 도중에 쓰기가 섞이면 스냅샷의 의미가
     * 흐려진다.
     */
    void completeRun(long runId, VerificationResult result);

    /**
     * 규칙을 돌리지도 못하고 끝난 실행을 닫는다.
     *
     * <p>규칙 하나가 실패한 경우는 여기로 오지 않는다. 그건 {@link RuleOutcome#failed}로 기록되어 정상 경로로
     * {@code ERROR} 판정을 받는다. 이 메서드는 스냅샷을 열지 못하는 등 규칙 결과 자체가 없는 경우를 위한 것이다.
     *
     * <p>닫지 않으면 {@code finished_at}이 영원히 {@code NULL}로 남아 "아직 돌고 있다"로 보인다.
     */
    void failRun(long runId, LocalDateTime finishedAt);
}
