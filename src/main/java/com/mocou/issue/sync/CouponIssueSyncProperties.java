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

    // batchWindowMs보다 한참 커야 한다 — 로컬 버퍼에서 정상적으로 누적 중인
    // 엔트리(최대 batchWindowMs 동안 PEL에 안 ACK된 채로 있는 게 정상)를
    // "죽은 컨슈머가 두고 간 것"으로 착각해 재처리하지 않기 위함.
    @Min(1)
    private long pendingMinIdleMs = 30_000;

    // XPENDING 확인도 매 틱마다 하면 낭비라 이 간격으로만 확인한다.
    @Min(1)
    private long pendingCheckIntervalMs = 10_000;

    // PendingMessage.totalDeliveryCount(Redis가 직접 세는 누적 배달 횟수)가 이 값을
    // 넘으면 더 이상 재처리하지 않고 포기(보상+실패 로그)한다 — 별도 재시도
    // 카운터를 우리가 들고 다닐 필요가 없다.
    @Min(1)
    private int maxDeliveryCount = 5;

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

    public long getPendingMinIdleMs() {
        return pendingMinIdleMs;
    }

    public void setPendingMinIdleMs(long pendingMinIdleMs) {
        this.pendingMinIdleMs = pendingMinIdleMs;
    }

    public long getPendingCheckIntervalMs() {
        return pendingCheckIntervalMs;
    }

    public void setPendingCheckIntervalMs(long pendingCheckIntervalMs) {
        this.pendingCheckIntervalMs = pendingCheckIntervalMs;
    }

    public int getMaxDeliveryCount() {
        return maxDeliveryCount;
    }

    public void setMaxDeliveryCount(int maxDeliveryCount) {
        this.maxDeliveryCount = maxDeliveryCount;
    }
}
