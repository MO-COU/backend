package com.mocou.loadtest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SSM 명령으로 별도 EC2의 k6를 실행함. */
@Slf4j
@Component
public class SsmLoadTestRunnerGateway implements LoadTestRunnerGateway {

    private static final String RESULT_PREFIX = "MOCOU_RESULT=";

    private final SsmClient ssmClient;
    private final LoadTestSsmProperties properties;
    private final LoadTestRunRepository repository;
    private final LoadTestDbSyncMonitor dbSyncMonitor;
    private final ObjectMapper objectMapper;

    public SsmLoadTestRunnerGateway(
            SsmClient ssmClient,
            LoadTestSsmProperties properties,
            LoadTestRunRepository repository,
            LoadTestDbSyncMonitor dbSyncMonitor,
            ObjectMapper objectMapper) {
        this.ssmClient = ssmClient;
        this.properties = properties;
        this.repository = repository;
        this.dbSyncMonitor = dbSyncMonitor;
        this.objectMapper = objectMapper;
    }

    @Async("loadTestExecutor")
    @Override
    public void start(long runId, LoadTestStartRequest request) {
        try {
            validateConfiguration();
            String commandId = sendCommand(runId, request);
            log.info(
                    "k6 SSM 실행 요청 완료: runId={}, commandId={}, scenario={}, couponId={}",
                    runId,
                    commandId,
                    request.scenario(),
                    request.couponId());
            waitForCompletion(runId, request.couponId(), commandId);
        } catch (Exception exception) {
            repository.fail(runId);
            log.error("k6 SSM 실행 실패: runId={}", runId, exception);
        }
    }

    private String sendCommand(long runId, LoadTestStartRequest request) {
        String command = buildCommand(runId, request);
        return ssmClient
                .sendCommand(
                        SendCommandRequest.builder()
                                .instanceIds(properties.instanceId())
                                .documentName("AWS-RunShellScript")
                                .comment("MOCOU load test run " + runId)
                                .timeoutSeconds(properties.timeoutSeconds())
                                .parameters(Map.of("commands", List.of(command)))
                                .build())
                .command()
                .commandId();
    }

    private void waitForCompletion(long runId, long couponId, String commandId)
            throws InterruptedException {
        // SSM 완료까지 조회함. 완료 후 실행 결과를 저장함.
        Instant deadline = Instant.now().plusSeconds(properties.timeoutSeconds());
        while (Instant.now().isBefore(deadline)) {
            try {
                var invocation =
                        ssmClient.getCommandInvocation(
                                GetCommandInvocationRequest.builder()
                                        .commandId(commandId)
                                        .instanceId(properties.instanceId())
                                        .build());
                CommandInvocationStatus status = invocation.status();
                if (status == CommandInvocationStatus.SUCCESS) {
                    LoadTestRunResult result = parseResult(invocation.standardOutputContent());
                    repository.markSyncing(runId, result);
                    dbSyncMonitor.waitUntilComplete(couponId, result.issuedCount());
                    repository.completeDbSync(runId);
                    return;
                }
                if (status == CommandInvocationStatus.CANCELLED
                        || status == CommandInvocationStatus.TIMED_OUT
                        || status == CommandInvocationStatus.FAILED
                        || status == CommandInvocationStatus.CANCELLING) {
                    saveFailedResultWhenPresent(runId, invocation.standardOutputContent());
                    throw new IllegalStateException(
                            "SSM command failed: " + status + " " + invocation.standardErrorContent());
                }
            } catch (InvocationDoesNotExistException exception) {
                log.debug("SSM invocation 생성 대기: commandId={}", commandId);
            }
            Thread.sleep(Duration.ofSeconds(properties.pollIntervalSeconds()).toMillis());
        }
        throw new IllegalStateException("SSM command timeout: " + commandId);
    }

    private void saveFailedResultWhenPresent(long runId, String output) {
        try {
            repository.finish(runId, parseResult(output), LoadTestRunStatus.FAILED);
        } catch (RuntimeException exception) {
            log.warn("실패한 k6 실행의 측정 결과를 읽지 못했습니다: runId={}", runId, exception);
        }
    }

    private LoadTestRunResult parseResult(String output) {
        String json =
                output.lines()
                        .filter(line -> line.startsWith(RESULT_PREFIX))
                        .reduce((first, second) -> second)
                        .map(line -> line.substring(RESULT_PREFIX.length()))
                        .orElseThrow(() -> new IllegalStateException("k6 결과 마커가 없습니다"));
        try {
            JsonNode result = objectMapper.readTree(json);
            return new LoadTestRunResult(
                    result.path("requestedCount").asInt(),
                    result.path("issuedCount").asInt(),
                    result.path("soldOutCount").asInt(),
                    result.path("duplicateCount").asInt(),
                    result.path("errorCount").asInt(),
                    result.path("p95Ms").isNull() ? null : result.path("p95Ms").asInt());
        } catch (Exception exception) {
            throw new IllegalStateException("k6 결과를 읽을 수 없습니다", exception);
        }
    }

    private String buildCommand(long runId, LoadTestStartRequest request) {
        LoadTestScenario scenario = request.scenario();
        return "cd "
                + shellQuote(properties.workDirectory())
                + " && SUMMARY_FILE="
                + shellQuote("/tmp/mocou-run-" + runId + "-summary.json")
                + " TARGET="
                + shellQuote(properties.targetUrl())
                + " COUPON_ID="
                + request.couponId()
                + " VUS="
                + scenario.vus()
                + " RAMP_UP="
                + shellQuote(scenario.rampUpSeconds() + "s")
                + " EXPECTED_STOCK="
                + scenario.expectedStock()
                + " k6 run --quiet "
                + shellQuote(scenario.scriptPath());
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void validateConfiguration() {
        if (properties.instanceId() == null || properties.instanceId().isBlank()) {
            throw new IllegalStateException("MOCOU_INSTANCE_ID_K6가 필요합니다");
        }
        if (properties.targetUrl() == null || properties.targetUrl().isBlank()) {
            throw new IllegalStateException("MOCOU_K6_TARGET_URL이 필요합니다");
        }
    }
}
