package com.mocou.lifecycle.perf;

import java.time.LocalDateTime;
import java.util.List;

public record ExpirationJobRunSnapshot(
        String runKey,
        long chunkSize,
        ExpirationJobRunStatus status,
        Long jobExecutionId,
        LocalDateTime cutoffAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Long durationMs,
        String failureReason,
        List<ExpirationJobChunkResult> chunks) {}
