package com.mocou.lifecycle.perf;

public record ExpirationJobChunkResult(int sequence, int selectedCount, long durationMs) {}
