package com.mocou.loadtest;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** 선택한 회차와 시나리오로 부하 테스트를 실행함. */
@Service
public class LoadTestExecutionService {

    private final LoadTestRunnerGateway runnerGateway;
    private final LoadTestRunRepository repository;
    private final LoadTestRedisReadinessValidator redisReadinessValidator;

    public LoadTestExecutionService(
            LoadTestRunnerGateway runnerGateway,
            LoadTestRunRepository repository,
            LoadTestRedisReadinessValidator redisReadinessValidator) {
        this.runnerGateway = runnerGateway;
        this.repository = repository;
        this.redisReadinessValidator = redisReadinessValidator;
    }

    public synchronized LoadTestRunResponse start(LoadTestStartRequest request) {
        // 동시에 실행하면 결과 비교 불가. 한 번에 하나만 허용함.
        if (repository.existsRunning()) {
            throw new BusinessException(ErrorCode.LOAD_TEST_ALREADY_RUNNING);
        }
        // DB와 Redis가 발급 전 상태인지 확인함.
        repository.validateCouponReady(request.couponId(), request.scenario().expectedStock());
        redisReadinessValidator.validate(
                request.couponId(), request.scenario().expectedStock());
        // 실행 기록을 먼저 생성함. runId로 상태 조회함.
        long runId = repository.create(request.couponId(), request.scenario());
        runnerGateway.start(runId, request);
        return repository.find(runId);
    }

    public LoadTestRunResponse getResult(long runId) {
        return repository.find(runId);
    }
}
