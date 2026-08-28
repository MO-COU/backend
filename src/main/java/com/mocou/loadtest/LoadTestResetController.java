package com.mocou.loadtest;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 테스트를 되돌리는 요청을 받는다.
 *
 * <p>되돌릴 회차를 {@code couponId}로 지정한다. 종료된 회차는 거부하므로, 지난 회차를 잘못 지목해도 검증 대상인 발급
 * 300만 건이 사라지지 않는다.
 *
 * <p>몇 초 안에 끝나므로 결과를 바로 돌려준다. 검증 실행이 {@code 202}인 것과 다른 점이다.
 */
@RestController
@RequestMapping("/api/admin/load-test")
@RequiredArgsConstructor
@Tag(name = "부하 테스트 API", description = "k6 부하 테스트 실행·조회와 초기화 API")
public class LoadTestResetController {

    private final LoadTestResetService loadTestResetService;

    /**
     * 시연 회차를 발급 직전 상태로 되돌린다.
     *
     * <p>응답에 담긴 수가 곧 확인 수단이다. 부하 테스트에서 1만 건이 발급됐다면 {@code deletedIssues}가 1만이어야 한다.
     */
    @PostMapping("/reset")
    @Operation(
            summary = "부하 테스트 되돌리기",
            description =
                    "지정한 회차를 발급 직전 상태로 되돌린다. 그 회차의 발급·상태 이력·실패 기록·알림을 지우고 "
                            + "재고를 총 재고로 복구하며, Redis 재고와 컨슈머 그룹을 다시 세운다. "
                            + "검증 실행 기록도 함께 지운다. "
                            + "응답에 담긴 건수가 확인 수단이다 — 1만 건 발급됐다면 deletedIssues가 1만이어야 한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "되돌리기 완료. 지운 건수가 응답에 담긴다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "존재하지 않는 couponId"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "종료된 회차를 지정했거나(LOAD_TEST_TARGET_CLOSED), "
                                + "컨슈머가 아직 DB에 반영 중이다(LOAD_TEST_SYNC_IN_PROGRESS). "
                                + "후자는 잠시 뒤 다시 요청하면 된다")
    })
    public ResponseEntity<ApiResponse<LoadTestResetResult>> reset(
            @Parameter(
                            description = "되돌릴 회차. 종료된 회차는 검증 대상 데이터를 담고 있어 거부한다",
                            example = "302")
                    @RequestParam
                    long couponId) {
        return ResponseEntity.ok(ApiResponse.success(loadTestResetService.reset(couponId)));
    }
}
