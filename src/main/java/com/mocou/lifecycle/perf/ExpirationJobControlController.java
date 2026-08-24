package com.mocou.lifecycle.perf;

import com.mocou.global.response.ApiResponse;
import com.mocou.global.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("perf")
@ConditionalOnExpression(
        "'${mocou.perf.expiration-control-enabled:false}' == 'true' "
                + "&& '${mocou.lifecycle.expiration.scheduler-enabled:true}' == 'false'")
@RequestMapping("/internal/perf/expiration-jobs")
public class ExpirationJobControlController {

    private final ExpirationJobControlService service;

    public ExpirationJobControlController(ExpirationJobControlService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public ApiResponse<ExpirationJobCapabilitiesResponse> capabilities() {
        return ApiResponse.success(new ExpirationJobCapabilitiesResponse(true, false, 1, 10000));
    }

    @PostMapping
    public ResponseEntity<?> submit(
            @Valid @RequestBody ExpirationJobControlRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(
                            ApiResponse.success(
                                    service.submit(
                                            request.runKey(),
                                            request.chunkSize(),
                                            request.couponId())));
        } catch (IllegalStateException exception) {
            String reason = exception.getMessage();
            HttpStatus status = "EXECUTOR_UNAVAILABLE".equals(reason)
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(ApiResponse.error(ErrorCode.INVALID_STATE_TRANSITION, reason));
        }
    }

    @GetMapping("/{runKey}")
    public ResponseEntity<ApiResponse<ExpirationJobRunSnapshot>> find(@PathVariable String runKey) {
        ExpirationJobRunSnapshot snapshot = service.find(runKey);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(snapshot));
    }
}
