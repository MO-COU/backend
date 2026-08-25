package com.mocou.consistency;

/**
 * 위반 대상의 종류. {@code verification_violation.target_type}에 저장된다.
 *
 * <p>규칙 구현마다 문자열을 직접 적으면 같은 대상을 다르게 표기할 여지가 생긴다. 리포트가 대상 종류로 묶어 보여주므로 표기가 갈리면 집계가
 * 어긋난다.
 */
public enum ViolationTarget {

    /** 쿠폰 하나. 식별자는 {@code coupon_id}. */
    COUPON,

    /** 회원 하나. 식별자는 {@code member_id}. */
    MEMBER,

    /** 발급 건 하나. 식별자는 {@code coupon_issue_id}. */
    COUPON_ISSUE,

    /** 상태 이력 한 행. 식별자는 {@code history_id}. */
    COUPON_ISSUE_HISTORY,

    /** 재고 한 행. 식별자는 {@code coupon_stock_id}. */
    COUPON_STOCK,

    /** 쿠폰과 회원의 쌍. 1차 식별자가 {@code coupon_id}, 2차가 {@code member_id}. */
    COUPON_MEMBER_PAIR
}
