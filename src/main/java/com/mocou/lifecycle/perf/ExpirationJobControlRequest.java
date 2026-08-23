package com.mocou.lifecycle.perf;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ExpirationJobControlRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String runKey,
        @Min(1) @Max(10000) int chunkSize) {}
