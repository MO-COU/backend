package com.mocou.lifecycle;

import com.mocou.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서버 재시작 없이 만료 스케줄러의 자동 실행 상태를 제어한다. */
@RestController
@RequestMapping("/internal/lifecycle/expiration-scheduler")
@Tag(name = "Lifecycle", description = "쿠폰 생명주기 제어 API")
public class ExpirationSchedulerControlController {

    private final ExpirationSchedulerState schedulerState;

    public ExpirationSchedulerControlController(ExpirationSchedulerState schedulerState) {
        this.schedulerState = schedulerState;
    }

    @GetMapping
    @Operation(summary = "만료 스케줄러 자동 실행 상태 조회")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "현재 자동 실행 상태 조회 성공")
    })
    public ResponseEntity<ApiResponse<ExpirationSchedulerStateResponse>> getState() {
        return ResponseEntity.ok(ApiResponse.success(currentState()));
    }

    @PutMapping
    @Operation(summary = "만료 스케줄러 자동 실행 상태 변경")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "자동 실행 상태 변경 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "enabled 값 누락 또는 null")
    })
    public ResponseEntity<ApiResponse<ExpirationSchedulerStateResponse>> updateState(
            @Valid @RequestBody ExpirationSchedulerStateRequest request) {
        // 이미 실행 중인 Batch는 건드리지 않고, 이후 스케줄 실행의 허용 여부만 바꾼다.
        schedulerState.setEnabled(request.enabled());
        return ResponseEntity.ok(ApiResponse.success(currentState()));
    }

    private ExpirationSchedulerStateResponse currentState() {
        return new ExpirationSchedulerStateResponse(schedulerState.isEnabled());
    }
}
