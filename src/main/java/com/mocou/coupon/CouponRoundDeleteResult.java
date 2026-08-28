package com.mocou.coupon;

/**
 * 회차 하나를 지우며 사라진 것들의 건수.
 *
 * <p>삭제는 되돌릴 수 없어 <b>이 건수가 유일한 기록</b>이다. 로그로만 남기면 API를 부른 쪽은 무엇이
 * 사라졌는지 알 수 없다. 그래서 {@code 204 No Content}가 아니라 {@code 200}에 본문을 담는다.
 */
public record CouponRoundDeleteResult(
        long couponId,
        int deletedIssues,
        int deletedHistories,
        int deletedFailureLogs,
        int deletedNotifications,
        int deletedVerificationRuns,
        int deletedIssueRuns) {}
