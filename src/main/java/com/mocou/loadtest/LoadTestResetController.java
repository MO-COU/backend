package com.mocou.loadtest;

import com.mocou.global.response.ApiResponse;
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
public class LoadTestResetController {

    private final LoadTestResetService loadTestResetService;

    /**
     * 시연 회차를 발급 직전 상태로 되돌린다.
     *
     * <p>응답에 담긴 수가 곧 확인 수단이다. 부하 테스트에서 1만 건이 발급됐다면 {@code deletedIssues}가 1만이어야 한다.
     */
    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<LoadTestResetResult>> reset(@RequestParam long couponId) {
        return ResponseEntity.ok(ApiResponse.success(loadTestResetService.reset(couponId)));
    }
}
