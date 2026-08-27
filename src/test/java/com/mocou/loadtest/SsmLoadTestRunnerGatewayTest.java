package com.mocou.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import tools.jackson.databind.ObjectMapper;

class SsmLoadTestRunnerGatewayTest {

    private SsmLoadTestRunnerGateway gateway() {
        LoadTestSsmProperties properties =
                new LoadTestSsmProperties(
                        "ap-northeast-2",
                        "i-k6",
                        "/home/ubuntu/mocou-backend",
                        "http://app:8080",
                        2,
                        900);
        return new SsmLoadTestRunnerGateway(
                mock(SsmClient.class),
                properties,
                mock(LoadTestRunRepository.class),
                mock(LoadTestDbSyncMonitor.class),
                new ObjectMapper());
    }

    @Test
    void separatesK6LogAndLeavesOnlyResultMarkerOnSsmStdout() {
        String command =
                gateway().buildCommand(
                                15L, new LoadTestStartRequest(301L, LoadTestScenario.V1_RAMP_20000));

        assertThat(command)
                .contains("> '/tmp/mocou-run-15-k6.log' 2>&1")
                .contains("printf 'MOCOU_RESULT='")
                .contains("cat '/tmp/mocou-run-15-summary.json'")
                .contains("exit 0")
                .contains("exit $K6_EXIT");
    }

    @Test
    @DisplayName("k6를 실행하기 전에 열 수 있는 소켓 수를 시나리오 규모 이상으로 올린다")
    void raisesFileDescriptorLimitBeforeRunningK6() {
        String command =
                gateway().buildCommand(
                                15L, new LoadTestStartRequest(301L, LoadTestScenario.V3_SPIKE_50000));

        // SSM Run Command는 로그인 세션이 아니라 soft limit이 1024로 남는다. 그대로면 VU 대부분이
        // 연결조차 못 연다.
        assertThat(command).contains("ulimit -n 262144");
        assertThat(command.indexOf("ulimit -n"))
                .as("ulimit은 k6 실행보다 먼저 나와야 한다")
                .isLessThan(command.indexOf("k6 run"));
        assertThat(262_144)
                .as("최대 시나리오의 VU 수를 감당해야 한다")
                .isGreaterThan(LoadTestScenario.V3_SPIKE_50000.vus());
    }
}
