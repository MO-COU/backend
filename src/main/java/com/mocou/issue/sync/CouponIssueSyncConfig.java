package com.mocou.issue.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@link CouponIssueSyncProperties}를 빈으로 등록한다.
 *
 * <p>{@code @EnableScheduling}은 {@code CouponExpirationBatchConfig}에서 이미 앱 전역으로
 * 켜져 있어 여기서 다시 선언하지 않는다.
 */
@Configuration
@EnableConfigurationProperties(CouponIssueSyncProperties.class)
public class CouponIssueSyncConfig {
}
