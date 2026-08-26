package com.mocou.coupon;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 회차 추가 요청.
 *
 * <p>{@code closeAt}과 {@code name}은 받지 않으면 서비스가 기본값을 채운다. 여기에 {@code @NotNull}을 걸면
 * 선택 항목이 아니게 되므로 검증하지 않는다.
 *
 * <p>{@code openAt}이 과거여도 막지 않는다. 지금 시각을 주면 즉시 열린 회차가 되어 "만들고 바로 부하 주기"에 쓸 수 있다.
 * 과거 시각이라고 데이터가 깨지지는 않으며, {@code closeAt}까지 지났다면 발급이 되지 않을 뿐이다.
 *
 * @param totalQuantity 회차 재고
 * @param openAt 발급 시작 시각
 * @param closeAt 발급 종료 시각. 비우면 {@code openAt} 당일 23:59:59
 * @param name 쿠폰 이름. 비우면 회차 번호로 만든다
 */
public record CouponRoundRequest(
        @NotNull(message = "재고는 필수입니다") @Min(value = 1, message = "재고는 1 이상이어야 합니다")
                Integer totalQuantity,
        @NotNull(message = "발급 시작 시각은 필수입니다") LocalDateTime openAt,
        LocalDateTime closeAt,
        String name) {}
