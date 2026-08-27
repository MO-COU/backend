package com.mocou.loadtest;

/**
 * 부하 테스트가 남긴 것을 지우고 재고를 되돌린다.
 *
 * <p>지우는 순서는 여기서 강제하지 않는다. FK가 걸려 있어 자식부터 지워야 하지만, 그 순서를 아는 것은
 * {@link LoadTestResetService}의 몫이다. 이 계층은 각각을 어떻게 지우는지만 안다.
 *
 * <p>모든 삭제 메서드가 건수를 돌려준다. 되돌리기가 온전했는지는 그 수로만 확인할 수 있다.
 */
public interface LoadTestResetRepository {

    /**
     * 쿠폰의 현재 상태. 없는 쿠폰이면 {@code null}.
     *
     * <p>되돌려도 되는 대상인지는 판단하지 않는다. 상태만 주고 서비스가 정한다.
     */
    String findStatus(long couponId);

    /**
     * 상태 이력을 지운다.
     *
     * <p>{@code coupon_issue_history}에는 {@code coupon_id}가 없어 발급 테이블을 거쳐야 한다.
     */
    int deleteHistories(long couponId);

    int deleteIssues(long couponId);

    int deleteFailureLogs(long couponId);

    int deleteNotifications(long couponId);

    /**
     * 검증 기록을 전부 지우고 지운 실행 수를 돌려준다.
     *
     * <p>위반 상세와 규칙 결과도 함께 지운다. FK가 모두 {@code NO ACTION}이라 자식이 남아 있으면 부모를 지울 수 없다.
     *
     * <p>어느 검증이 이 쿠폰을 본 것인지 골라낼 수 없어 전부 지운다. {@code verification_run.issue_run_id}가
     * 채워지지 않아 실행별 구분이 불가능하다.
     */
    int deleteAllVerificationRecords();

    /**
     * 잔여 재고를 총 재고로 되돌린다.
     *
     * <p>행을 지우지 않는다. 지우면 쿠폰의 재고 정의가 사라져 {@code STOCK_MISMATCH} 규칙이 위반으로 잡는다.
     */
    int restoreStock(long couponId);
}
