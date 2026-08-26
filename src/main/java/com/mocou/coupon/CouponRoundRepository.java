package com.mocou.coupon;

import java.time.LocalDateTime;

/** 회차를 만든다. */
public interface CouponRoundRepository {

    /**
     * 다음 회차 번호.
     *
     * <p>{@code AUTO_INCREMENT}에 맡기지 않는 이유는 <b>이름 기본값에 회차 번호가 들어가기</b> 때문이다. 번호를
     * 모르면 이름을 만들 수 없고, 나중에 채우려면 INSERT 뒤에 UPDATE를 한 번 더 해야 한다.
     *
     * <p>{@code datagen}의 회차 생성도 {@code coupon_id}를 명시적으로 넣으므로 방식이 같다.
     *
     * <p>동시에 두 요청이 오면 같은 번호를 계산해 PK 충돌이 날 수 있다. 관리자가 회차를 만드는 API라 동시 호출이 사실상 없고,
     * 나더라도 두 번째 요청이 실패할 뿐 데이터가 깨지지 않는다.
     */
    long nextRoundNumber();

    /** {@code coupon}과 {@code coupon_stock}에 한 회차를 넣는다. */
    void insertRound(
            long couponId,
            String name,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            int totalQuantity);
}
