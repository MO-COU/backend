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
    // notification.status를 FAILED로 확정한다. CouponIssueSyncProperties의 메인
    // 스트림 재시도 한도와 맞춘 값이다 — 알림은 DLQ 같은 2단계 유예가 없어
    // 발급 쪽의 "빠른 재시도" 계층에 맞춘다.
    @Min(1)
    private int maxDeliveryCount = 3;

    // 이보다 최근에 생성된 PENDING row는 폴링(안전망 경로) 대상에서 제외한다. 즉시 경로가
    // 커밋 직후 같은 row를 처리 중일 수 있는 시간대라, 폴링이 여기 끼어들면 같은 알림이
    // 두 번 발송될 수 있다. 즉시 경로는 findPending을 거치지 않으므로 이 값에 영향받지
    // 않는다 - 지금(로그만 남기는 모킹)은 배치 처리가 사실상 순간이라 여유값이지만, 실제
    // 발송 연동으로 배치 처리 시간이 늘면 그보다 크게 잡아야 한다.
    @Min(0)
    private int minDispatchAgeSeconds = 5;

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

    public int getMinDispatchAgeSeconds() {
        return minDispatchAgeSeconds;
    }

    public void setMinDispatchAgeSeconds(int minDispatchAgeSeconds) {
        this.minDispatchAgeSeconds = minDispatchAgeSeconds;
    }
}
