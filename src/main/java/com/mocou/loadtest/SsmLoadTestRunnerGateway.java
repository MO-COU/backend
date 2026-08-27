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

    /** 최대 시나리오(V3, 5만 VU)의 두 배 남짓. k6 EC2의 hard limit(524288) 안이라 특권 없이 올라간다. */
    private static final int FILE_DESCRIPTOR_LIMIT = 262_144;

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

    String buildCommand(long runId, LoadTestStartRequest request) {
        LoadTestScenario scenario = request.scenario();
        String summaryFile = "/tmp/mocou-run-" + runId + "-summary.json";
        String logFile = "/tmp/mocou-run-" + runId + "-k6.log";
        return "cd "
                + shellQuote(properties.workDirectory())
                // VU 하나가 소켓 하나를 연다. SSM Run Command는 PAM 로그인 세션이 아니라
                // /etc/security/limits.conf가 적용되지 않고 soft limit이 기본 1024로 남는다.
                // 그대로 두면 2만 VU에서 연결을 못 열어 대부분 request timeout이 된다.
                // hard limit 안에서 올리는 것이라 특권이 필요 없다.
                + " && ulimit -n "
                + FILE_DESCRIPTOR_LIMIT
                + " && SUMMARY_FILE="
                + shellQuote(summaryFile)
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
                + shellQuote(scenario.scriptPath())
                + " > "
                + shellQuote(logFile)
                + " 2>&1; K6_EXIT=$?; "
                + "if [ -s "
                + shellQuote(summaryFile)
                + " ]; then printf '"
                + RESULT_PREFIX
                + "'; cat "
                + shellQuote(summaryFile)
                + "; printf '\\n'; exit 0; "
                + "else tail -c 8000 "
                + shellQuote(logFile)
                + " >&2; exit $K6_EXIT; fi";
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
