package com.mocou.consistency;

import java.util.List;

public record VerificationRuleResultResponse(
        long ruleResultId,
        String ruleName,
        String status,
        long checkedCount,
        long violationCount,
        String failureReason,
        List<VerificationViolationResponse> violations) {}
