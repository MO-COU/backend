package com.mocou.loadtest;

import com.mocou.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 테스트를 되돌리는 요청을 받는다.
 *
 * <p>파라미터가 없다. 되돌릴 쿠폰을 호출한 쪽이 지정하면 지난 회차를 지정할 수 있게 되고, 그 회차에는 검증 대상인 발급 300만 건이
 * 들어 있다. 대상은 서버가 발급을 여는 쿠폰에서 찾는다.
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
    public ResponseEntity<ApiResponse<LoadTestResetResult>> reset() {
        return ResponseEntity.ok(ApiResponse.success(loadTestResetService.reset()));
    }
}
