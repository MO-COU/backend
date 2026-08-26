package com.mocou.loadtest;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 부하 테스트", description = "k6 부하 테스트 실행과 상태 조회")
@RestController
@RequestMapping("/api/admin/load-tests")
public class LoadTestExecutionController {

    private final LoadTestExecutionService service;

    public LoadTestExecutionController(LoadTestExecutionService service) {
        this.service = service;
    }

    @Operation(
            summary = "부하 테스트 실행",
            description =
                    "관리자가 선택한 쿠폰 회차에 선택한 시나리오를 AWS SSM을 통해 k6 EC2에서 실행한다. "
                            + "회차와 시나리오는 독립적으로 선택할 수 있다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202", description = "부하 테스트 실행 요청 접수"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "쿠폰 ID 또는 시나리오 입력 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "쿠폰 회차를 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "다른 테스트 실행 중 또는 대상 회차가 초기 상태가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503", description = "실행 전 Redis 상태 조회 불가")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<LoadTestRunResponse>> start(
            @Valid @RequestBody LoadTestStartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.start(request)));
    }

    @Operation(
            summary = "부하 테스트 상태 조회",
            description =
                    "실행 번호로 진행 상태와 결과를 조회한다. RUNNING은 k6 실행 중, SYNCING은 DB 적재 대기 중, "
                            + "SUCCESS는 DB 적재까지 완료된 상태다. finishedAt은 k6 종료 시각이고 "
                            + "dbSyncFinishedAt은 DB 적재 완료 시각이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "실행 상태 및 결과 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "부하 테스트 실행 기록을 찾을 수 없음")
    })
    @GetMapping("/{runId}")
    public ResponseEntity<ApiResponse<LoadTestRunResponse>> getResult(
            @PathVariable long runId) {
        return ResponseEntity.ok(ApiResponse.success(service.getResult(runId)));
    }
}
