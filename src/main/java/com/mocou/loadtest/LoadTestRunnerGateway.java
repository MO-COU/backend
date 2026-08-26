package com.mocou.loadtest;

public interface LoadTestRunnerGateway {

    void start(long runId, LoadTestStartRequest request);
}
