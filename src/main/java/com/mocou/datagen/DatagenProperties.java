package com.mocou.datagen;

import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 더미데이터 생성 규모와 기준값.
 *
 * @param baseTime 기준 시각 T0. 지정하지 않으면 DB 현재 시각을 쓰고 로그에 남긴다.
 */
@Validated
@ConfigurationProperties(prefix = "mocou.datagen")
record DatagenProperties(
        @Min(1) @DefaultValue("1000000") int memberCount,
        @Min(1) @DefaultValue("3000000") int issueCount,
        @Min(1) @DefaultValue("8") int dummyCouponCount,
        @Min(1) @DefaultValue("500000") int dummyCouponTotalQuantity,
        @Min(1) @DefaultValue("10000") int demoCouponTotalQuantity,
        LocalDateTime baseTime,
        @DefaultValue("20260819") long seed,
        @Min(1) @DefaultValue("10000") int chunkSize) {}
