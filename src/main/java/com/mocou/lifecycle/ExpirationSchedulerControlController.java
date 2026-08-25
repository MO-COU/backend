package com.mocou.lifecycle;

import com.mocou.global.response.ApiResponse;
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
public class ExpirationSchedulerControlController {

    private final ExpirationSchedulerState schedulerState;

    public ExpirationSchedulerControlController(ExpirationSchedulerState schedulerState) {
        this.schedulerState = schedulerState;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ExpirationSchedulerStateResponse>> getState() {
        return ResponseEntity.ok(ApiResponse.success(currentState()));
    }

    @PutMapping
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
