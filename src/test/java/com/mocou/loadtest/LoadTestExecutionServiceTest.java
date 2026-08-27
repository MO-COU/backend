package com.mocou.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.mocou.issue.sync.CouponSyncTargetChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class LoadTestExecutionServiceTest {

    @Mock private LoadTestRunnerGateway runnerGateway;
    @Mock private LoadTestRunRepository repository;
    @Mock private LoadTestRedisReadinessValidator redisReadinessValidator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private LoadTestExecutionService service;

    @Test
    void startsSelectedScenarioForSelectedCouponRound() {
        LoadTestStartRequest request =
                new LoadTestStartRequest(301L, LoadTestScenario.V2_SPIKE_20000);
        LoadTestRunResponse expected = response(77L, 301L, LoadTestScenario.V2_SPIKE_20000);
        given(repository.create(301L, LoadTestScenario.V2_SPIKE_20000)).willReturn(77L);
        given(repository.find(77L)).willReturn(expected);

        LoadTestRunResponse actual = service.start(request);

        assertThat(actual).isEqualTo(expected);
        verify(repository).validateCouponReady(301L, 10_000);
        verify(redisReadinessValidator).validate(301L, 10_000);
        verify(eventPublisher).publishEvent(new CouponSyncTargetChangedEvent(301L));
        verify(runnerGateway).start(77L, request);
    }

    private LoadTestRunResponse response(
            long runId, long couponId, LoadTestScenario scenario) {
        return new LoadTestRunResponse(
                runId,
                couponId,
                scenario,
                LoadTestRunStatus.RUNNING,
                scenario.vus(),
                scenario.rampUpSeconds(),
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                "부하 테스트를 시작했습니다.");
    }
}
