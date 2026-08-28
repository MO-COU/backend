package com.mocou.consistency;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * <p>실행 번호로 진행 상태와 결과를 다시 조회할 수 있다.
 */
@RestController
@RequestMapping("/api/admin/verifications")
@Tag(name = "정합성 검증 API", description = "쿠폰 발급 이력 정합성 검증 API")
public class VerificationController {

    private final VerificationLauncher launcher;
    private final VerificationResultQueryService queryService;

    public VerificationController(
            VerificationLauncher launcher, VerificationResultQueryService queryService) {
        this.launcher = launcher;
        this.queryService = queryService;
    }

    /**
     * 검증을 시작하고 실행 번호를 돌려준다.
     *
     * <p>실행 자체는 백그라운드에서 돌아 응답이 즉시 나간다. 검증이 300만 건을 훑느라 몇 분이 걸려 동기로 응답하면 타임아웃에 걸린다.
     *
     * @param issueRunId 부하 테스트 직후 그 실행을 검증할 때 지정한다. 비우면 더미데이터를 포함한 DB 전체를 검증한다
     */
    @PostMapping
    @Operation(
            summary = "정합성 검증 실행",
            description =
                    "발급 이력 전체를 대상으로 규칙 8종을 검사한다. 300만 건 기준 약 90초가 걸려 "
                            + "백그라운드에서 돌며, 202와 함께 돌아오는 runId로 결과를 따로 조회한다. "
                            + "부하 테스트 직후라면 Redis Stream이 비워진 뒤에 실행해야 한다 — "
                            + "동기화가 남아 있으면 Redis·DB 교차 규칙이 판정 불가가 되어 전체가 ERROR로 끝난다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "검증을 시작했다. 끝난 것이 아니므로 runId로 결과를 조회해야 한다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "이미 진행 중인 검증이 있다. 겹쳐 돌리면 결과 행이 둘로 갈린다. "
                                + "시작한 지 5분이 지나도 끝나지 않은 실행은 죽은 것으로 보고 새 실행을 허용한다")
    })
    public ResponseEntity<ApiResponse<VerificationStartResponse>> start(
            @Parameter(
                            description =
                                    "부하 테스트 직후 그 실행을 검증할 때 지정한다. "
                                            + "비우면 더미데이터를 포함한 DB 전체를 검증한다",
                            example = "5")
                    @RequestParam(required = false)
                    Long issueRunId) {
        long runId = launcher.launch(issueRunId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(VerificationStartResponse.started(runId)));
    }

    @GetMapping("/{runId}")
    @Operation(
            summary = "정합성 검증 결과 조회",
            description =
                    "검증 실행 번호로 진행 상태, 최종 판정, 검사 건수와 규칙별 결과를 조회합니다. "
                            + "실행 중인 검증도 조회할 수 있으며, 완료 전에는 최종 판정이 확정되지 않을 수 있습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "정합성 검증 결과 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "정합성 검증 실행 기록을 찾을 수 없음 (VERIFICATION_RUN_NOT_FOUND)")
    })
    public ResponseEntity<ApiResponse<VerificationResultResponse>> getResult(
            @Parameter(description = "정합성 검증 실행 ID", example = "11") @PathVariable long runId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getResult(runId)));
    }
}
