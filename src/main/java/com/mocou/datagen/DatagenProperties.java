package com.mocou.datagen;

import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 더미데이터 생성 규모와 기준값.
 *
 * <p>쿠폰은 매주 월요일 열리는 회차로 모형을 잡는다. {@code roundCount × roundStock}이 발급 이력의 상한이며, 모든 회차가
 * 매진된다는 전제에서 요구 수량 300만 건이 나온다.
 *
 * @param baseTime 기준 시각 T0. 지정하지 않으면 DB 현재 시각을 쓰고 로그에 남긴다.
 */
@Validated
@ConfigurationProperties(prefix = "mocou.datagen")
record DatagenProperties(
        @Min(1) @DefaultValue("1000000") int memberCount,
        @Min(1) @DefaultValue("300") int roundCount,
        @Min(1) @DefaultValue("10000") int roundStock,
        @Min(1) @DefaultValue("10000") int demoCouponTotalQuantity,
        LocalDateTime baseTime,
        @DefaultValue("20260819") long seed,
        @Min(1) @DefaultValue("10000") int chunkSize) {}
