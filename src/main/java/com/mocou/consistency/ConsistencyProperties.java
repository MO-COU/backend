package com.mocou.consistency;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 검증 실행 설정.
 *
 * @param violationLimit 규칙당 저장할 위반 상세의 최대 건수. 집계에는 전체 수가 기록되고 이 값은 목록에만 걸린다
 * @param graceMultiplier 만료 배치 주기에 곱할 배수. 절대 시간을 두지 않는 이유는 배치 주기가 부하 테스트에서 조정되기
 *     때문이다. 주기를 1초로 낮췄는데 유예가 5분으로 남아 있으면 300주기가 되어 아무것도 검출하지 못한다
 * @param staleRunMinutes 이 시간이 지나도 끝나지 않은 실행은 죽은 것으로 본다. 실행 중 애플리케이션이 죽으면
 *     {@code finished_at}이 영원히 {@code NULL}로 남는데, 그것을 계속 "진행 중"으로 보면 그다음부터 검증을 아예
 *     못 하게 된다. 실제 소요 시간을 재고 여유를 둬서 정한다
 */
@Validated
@ConfigurationProperties(prefix = "mocou.consistency")
public record ConsistencyProperties(
        @Min(1) @DefaultValue("1000") int violationLimit,
        @Min(1) @DefaultValue("5") int graceMultiplier,
        @Min(1) @DefaultValue("30") int staleRunMinutes) {}
