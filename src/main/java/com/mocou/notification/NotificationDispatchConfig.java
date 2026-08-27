package com.mocou.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// notification-stream: NotificationDispatchProperties를 빈으로 등록한다.
// @EnableScheduling은 CouponExpirationBatchConfig에서 이미 앱 전역으로 켜져 있어 여기서 다시 선언하지 않는다.
@Configuration
@EnableConfigurationProperties(NotificationDispatchProperties.class)
public class NotificationDispatchConfig {
}
