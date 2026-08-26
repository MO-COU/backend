package com.mocou.loadtest;

import java.time.OffsetDateTime;

public record LoadTestRunResponse(
        long runId,
        long couponId,
        LoadTestScenario scenario,
        LoadTestRunStatus status,
        int users,
        int rampUpSeconds,
        int requestedCount,
        int issuedCount,
        int failedCount,
        int soldOutCount,
        int duplicateCount,
        int errorCount,
        Integer p95Ms,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String message) {}
