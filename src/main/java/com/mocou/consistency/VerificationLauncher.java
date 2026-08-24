package com.mocou.consistency;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 검증 실행을 시작시키고 백그라운드로 넘긴다.
 *
 * <p>실행 번호를 만드는 일(INSERT 하나)만 호출한 스레드에서 하고, 규칙을 도는 긴 작업은 별도 스레드로 넘긴다. 그래야 HTTP 응답이
 * 즉시 나가면서도 "돌고 있다"는 사실이 DB에 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationLauncher {

    private final ConsistencyVerifier verifier;
    private final VerificationRepository repository;
    private final ConsistencyProperties properties;
    private final ExecutorService verificationExecutor;

    /**
     * 진행 중인 검증이 없으면 새로 시작한다.
     *
     * <p>겹쳐 돌리면 300만 건 스캔이 두 배가 되고 결과 행도 둘로 갈린다. 데이터가 깨지지는 않지만(읽기 전용) 시연 중 버튼을 두 번
     * 누르는 일이 실제로 생기므로 막는다.
     *
     * <p>"진행 중"의 판단에 시간 제한을 둔다. 실행 중 애플리케이션이 죽으면 {@code finished_at}이 영원히
     * {@code NULL}로 남는데, 그것까지 진행 중으로 보면 그다음부터 검증을 아예 못 하게 된다.
     *
     * @throws BusinessException 이미 진행 중인 검증이 있으면
     */
    public long launch(Long issueRunId) {
        LocalDateTime staleBoundary = LocalDateTime.now().minusMinutes(properties.staleRunMinutes());
        if (repository.hasRunningSince(staleBoundary)) {
            throw new BusinessException(ErrorCode.VERIFICATION_ALREADY_RUNNING);
        }

        long runId = verifier.startRun(issueRunId);
        log.info("정합성 검증 시작 (run {}, 검증 대상 {})", runId, issueRunId == null ? "DB 전체" : issueRunId);

        verificationExecutor.execute(() -> verifier.runAndComplete(runId));
        return runId;
    }
}
