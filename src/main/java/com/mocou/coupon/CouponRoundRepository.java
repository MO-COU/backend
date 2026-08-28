package com.mocou.coupon;

import java.time.LocalDateTime;

/** 회차를 만들고 지운다. */
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

    /** 쿠폰의 상태. 없으면 {@code null}. */
    String findStatus(long couponId);

    /**
     * 회차 하나와 거기 딸린 모든 기록을 지운다.
     *
     * <p>순서를 메서드 하나로 감싸는 이유는 <b>지우는 차례를 FK가 정하기</b> 때문이다. 열 단계를 호출부에
     * 늘어놓으면 순서를 바꿔도 컴파일이 통과하고, 어긋난 순간 {@code ERROR 1451}로만 드러난다.
     *
     * <p>호출부가 트랜잭션을 열어야 한다. 중간에 끊기면 절반만 지워진 회차가 남는다.
     */
    CouponRoundDeleteResult deleteRound(long couponId);
}
