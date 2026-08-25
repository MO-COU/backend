package com.mocou.consistency;

import java.time.LocalDateTime;
import java.util.List;

public record VerificationResultResponse(
        long runId,
        Long issueRunId,
        String status,
        String verdict,
        LocalDateTime snapshotAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long checkedCount,
        long violationCount,
        List<VerificationRuleResultResponse> rules) {}
