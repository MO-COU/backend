package com.mocou.lifecycle.perf;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** perf 제어 요청의 runKey와 활성 실행을 한 프로세스 안에서 직렬화한다. */
public class ExpirationJobRunRegistry {

    private final Set<String> runKeys = new HashSet<>();
    private final Map<String, ExpirationJobRunSnapshot> runs = new LinkedHashMap<>();
    private String activeRunKey;

    public synchronized ExpirationJobReservation reserve(String runKey, int chunkSize) {
        if (runKeys.contains(runKey)) {
            return new ExpirationJobReservation(ExpirationJobReservationStatus.DUPLICATE_RUN_KEY);
        }
        if (activeRunKey != null) {
            return new ExpirationJobReservation(ExpirationJobReservationStatus.JOB_ALREADY_RUNNING);
        }
        runKeys.add(runKey);
        activeRunKey = runKey;
        runs.put(
                runKey,
                new ExpirationJobRunSnapshot(
                        runKey, chunkSize, ExpirationJobRunStatus.SUBMITTED, null, null, null, null, null, null, List.of()));
        return new ExpirationJobReservation(ExpirationJobReservationStatus.ACCEPTED);
    }

    public synchronized void markRunning(String runKey, LocalDateTime cutoffAt, LocalDateTime startedAt) {
        ExpirationJobRunSnapshot current = requiredRun(runKey);
        runs.put(
                runKey,
                new ExpirationJobRunSnapshot(
                        current.runKey(),
                        current.chunkSize(),
                        ExpirationJobRunStatus.RUNNING,
                        null,
                        cutoffAt,
                        startedAt,
                        null,
                        null,
                        null, List.of()));
    }

    public synchronized void complete(String runKey, long jobExecutionId, LocalDateTime endedAt, List<ExpirationJobChunkResult> chunks) {
        ExpirationJobRunSnapshot current = requiredRun(runKey);
        runs.put(
                runKey,
                new ExpirationJobRunSnapshot(
                        current.runKey(),
                        current.chunkSize(),
                        ExpirationJobRunStatus.COMPLETED,
                        jobExecutionId,
                        current.cutoffAt(),
                        current.startedAt(),
                        endedAt,
                        ChronoUnit.MILLIS.between(current.startedAt(), endedAt),
                        null, chunks));
        complete(runKey);
    }

    public synchronized void fail(String runKey, LocalDateTime endedAt, String failureReason) {
        ExpirationJobRunSnapshot current = requiredRun(runKey);
        Long durationMs = current.startedAt() == null
                ? null
                : ChronoUnit.MILLIS.between(current.startedAt(), endedAt);
        runs.put(
                runKey,
                new ExpirationJobRunSnapshot(
                        current.runKey(), current.chunkSize(), ExpirationJobRunStatus.FAILED, null,
                        current.cutoffAt(), current.startedAt(), endedAt,
                        durationMs, failureReason, List.of()));
        complete(runKey);
    }

    public synchronized void complete(String runKey) {
        if (runKey.equals(activeRunKey)) {
            activeRunKey = null;
        }
    }

    public synchronized ExpirationJobRunSnapshot find(String runKey) {
        return runs.get(runKey);
    }

    private ExpirationJobRunSnapshot requiredRun(String runKey) {
        ExpirationJobRunSnapshot run = runs.get(runKey);
        if (run == null) {
            throw new IllegalArgumentException("unknown runKey");
        }
        return run;
    }
}
