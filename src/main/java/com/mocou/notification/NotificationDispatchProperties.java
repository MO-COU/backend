package com.mocou.notification;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * poll-interval-ms(안전망 @Scheduled 호출 간격)는 여기 필드로 안 두고
 * NotificationDispatchConsumer의 @Scheduled(fixedDelayString = "${...}")에서
 * 플레이스홀더로 직접 읽는다 - CouponIssueSyncProperties와 같은 이유
 * (그 값을 실제로 쓰는 곳이 어노테이션 하나뿐이라 필드로 중복 선언해봐야
 * 아무 데서도 참조하지 않는 죽은 필드가 된다).
 */
/** outbox: NotificationDispatchConsumer의 폴링 배치 크기/재시도 한도 설정. */
@Validated
@ConfigurationProperties(prefix = "mocou.notification.dispatch")
public class NotificationDispatchProperties {

    @Min(1)
    private int batchSize = 100;

    // 이 값을 넘겨 재시도해도 계속 실패하면 더 이상 재시도하지 않고
    // notification.status를 FAILED로 확정한다.
    @Min(1)
    private int maxDeliveryCount = 5;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxDeliveryCount() {
        return maxDeliveryCount;
    }

    public void setMaxDeliveryCount(int maxDeliveryCount) {
        this.maxDeliveryCount = maxDeliveryCount;
    }
}
