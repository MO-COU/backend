package com.mocou.coupon;

import java.time.LocalDateTime;

/**
 * 회차 추가 결과.
 *
 * <p>서버가 채운 기본값을 그대로 담는다. {@code closeAt}과 {@code name}은 요청에서 비울 수 있으므로, 실제로 무엇이
 * 들어갔는지 호출한 쪽이 확인할 수 있어야 한다.
 *
 * <p>Redis 초기화 결과는 담지 않는다. 실패하면 예외가 나 회차 생성 자체가 실패하므로, <b>응답이 왔다는 것이 곧 초기화까지
 * 끝났다는 뜻</b>이다.
 *
 * @param couponId 부하 테스트에 넘길 번호
 */
public record CouponRoundResponse(
        long couponId,
        String name,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        int totalQuantity) {}
