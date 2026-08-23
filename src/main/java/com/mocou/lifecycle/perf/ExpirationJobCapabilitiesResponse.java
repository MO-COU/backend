package com.mocou.lifecycle.perf;

public record ExpirationJobCapabilitiesResponse(
        boolean controlEnabled, boolean schedulerEnabled, int minChunkSize, int maxChunkSize) {}
