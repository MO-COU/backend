package com.mocou.consistency;

import com.mocou.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정합성 검증 실행 요청을 받는다.
 *
 * <p>부하 테스트가 끝난 뒤 검증하는 흐름이라 프로필 러너 대신 API로 연다. 러너는 앱을 껐다 켜야 하는데, 이미 떠 있는 앱에 요청 한 번을
 * 보내는 편이 시연에 맞다.
 *
 * <p>결과 조회는 여기서 제공하지 않는다. 관리자 화면용 조회는 C팀 담당(`FR-5.3`)이며
 * {@code verification_run}·{@code verification_rule_result}를 읽으면 된다.
 */
@RestController
@RequestMapping("/api/admin/verifications")
public class VerificationController {

    private final VerificationLauncher launcher;

    public VerificationController(VerificationLauncher launcher) {
        this.launcher = launcher;
    }

    /**
     * 검증을 시작하고 실행 번호를 돌려준다.
     *
     * <p>실행 자체는 백그라운드에서 돌아 응답이 즉시 나간다. 검증이 300만 건을 훑느라 몇 분이 걸려 동기로 응답하면 타임아웃에 걸린다.
     *
     * @param issueRunId 부하 테스트 직후 그 실행을 검증할 때 지정한다. 비우면 더미데이터를 포함한 DB 전체를 검증한다
     */
    @PostMapping
    public ResponseEntity<ApiResponse<VerificationStartResponse>> start(
            @RequestParam(required = false) Long issueRunId) {
        long runId = launcher.launch(issueRunId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(VerificationStartResponse.started(runId)));
    }
}
