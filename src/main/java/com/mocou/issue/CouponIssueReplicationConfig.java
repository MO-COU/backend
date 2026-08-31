package com.mocou.issue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponIssueReplicationProperties.class)
public class CouponIssueReplicationConfig {
}
