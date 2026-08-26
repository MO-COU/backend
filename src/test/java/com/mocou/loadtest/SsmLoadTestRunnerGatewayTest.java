package com.mocou.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import tools.jackson.databind.ObjectMapper;

class SsmLoadTestRunnerGatewayTest {

    @Test
    void separatesK6LogAndLeavesOnlyResultMarkerOnSsmStdout() {
        LoadTestSsmProperties properties =
                new LoadTestSsmProperties(
                        "ap-northeast-2",
                        "i-k6",
                        "/home/ubuntu/mocou-backend",
                        "http://app:8080",
                        2,
                        900);
        SsmLoadTestRunnerGateway gateway =
                new SsmLoadTestRunnerGateway(
                        mock(SsmClient.class),
                        properties,
                        mock(LoadTestRunRepository.class),
                        mock(LoadTestDbSyncMonitor.class),
                        new ObjectMapper());

        String command =
                gateway.buildCommand(
                        15L, new LoadTestStartRequest(301L, LoadTestScenario.V1_RAMP_20000));

        assertThat(command)
                .contains("> '/tmp/mocou-run-15-k6.log' 2>&1")
                .contains("printf 'MOCOU_RESULT='")
                .contains("cat '/tmp/mocou-run-15-summary.json'")
                .contains("exit 0")
                .contains("exit $K6_EXIT");
    }
}
