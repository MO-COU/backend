package com.mocou.consistency;

public record VerificationViolationResponse(
        long violationId,
        String targetType,
        Long targetId,
        Long targetId2,
        String detail) {}
