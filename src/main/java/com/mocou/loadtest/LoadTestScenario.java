package com.mocou.loadtest;

/** 부하 테스트 조건. */
public enum LoadTestScenario {
    V1_RAMP_20000("load-test/rush-issue.js", 20_000, 60, 10_000),
    V2_SPIKE_20000("load-test/spike-issue.js", 20_000, 0, 10_000),
    V3_SPIKE_50000("load-test/spike-issue.js", 50_000, 0, 10_000),
    V4_RAMP_ONCE_20000("load-test/ramp-once-issue.js", 20_000, 60, 10_000),
    V5_RATE_4000_RPS("load-test/rate-issue.js", 20_000, 0, 10_000),
    V6_REPEAT_1_TO_3("load-test/repeat-issue.js", 20_000, 0, 10_000);

    private final String scriptPath;
    private final int vus;
    private final int rampUpSeconds;
    private final int expectedStock;

    LoadTestScenario(String scriptPath, int vus, int rampUpSeconds, int expectedStock) {
        this.scriptPath = scriptPath;
        this.vus = vus;
        this.rampUpSeconds = rampUpSeconds;
        this.expectedStock = expectedStock;
    }

    public String scriptPath() {
        return scriptPath;
    }

    public int vus() {
        return vus;
    }

    public int rampUpSeconds() {
        return rampUpSeconds;
    }

    public int expectedStock() {
        return expectedStock;
    }
}
