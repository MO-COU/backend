package com.mocou.loadtest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mocou.load-test.ssm")
public record LoadTestSsmProperties(
        String region,
        String instanceId,
        String workDirectory,
        String targetUrl,
        int pollIntervalSeconds,
        int timeoutSeconds) {}
