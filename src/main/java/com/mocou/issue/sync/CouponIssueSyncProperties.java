package com.mocou.issue.sync;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * poll-interval-ms(@Scheduled 호출 간격)는 여기 필드로 안 두고
 * CouponIssueSyncConsumer의 @Scheduled(fixedDelayString = "${...}")에서
 * 플레이스홀더로 직접 읽는다. 그 값을 실제로 쓰는 곳이 어노테이션 하나뿐이라
 * 필드로 중복 선언해봐야 아무 데서도 참조하지 않는 죽은 필드가 되기 때문
 * (CouponExpirationBatchProperties.fixedDelayMs가 그 사례).
 */
@Validated
@ConfigurationProperties(prefix = "mocou.issue.sync")
public class CouponIssueSyncProperties {

    @Min(1)
    private int chunkSize = 100;

    @Min(1)
    private long batchWindowMs = 5_000;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public long getBatchWindowMs() {
        return batchWindowMs;
    }

    public void setBatchWindowMs(long batchWindowMs) {
        this.batchWindowMs = batchWindowMs;
    }
}
