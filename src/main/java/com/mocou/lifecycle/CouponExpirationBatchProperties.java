package com.mocou.lifecycle;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mocou.lifecycle.expiration")
public class CouponExpirationBatchProperties {

    /** 기본값은 application.yml에서 관리하며, 이 객체는 청크 크기를 Tasklet에 전달한다. */
    @Min(1)
    private int chunkSize;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
}
