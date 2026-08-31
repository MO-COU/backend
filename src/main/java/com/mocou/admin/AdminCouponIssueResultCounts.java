package com.mocou.admin;

public record AdminCouponIssueResultCounts(
        long couponId,
        long totalRequests,
        long reserved,
        long failed,
        long soldOut,
        long duplicateIssue,
        long notOpenYet,
        long issueClosed,
        long stockNotInitialized,
        long metadataNotInitialized,
        long dlqFailed,
        long dbPersisted,
        long pendingOrRetrying) {

    public static AdminCouponIssueResultCounts of(
            long couponId,
            long reserved,
            long soldOut,
            long duplicateIssue,
            long notOpenYet,
            long issueClosed,
            long stockNotInitialized,
            long metadataNotInitialized,
            long dlqFailed) {
        long failed =
                Math.addExact(
                        Math.addExact(
                                Math.addExact(soldOut, duplicateIssue),
                                Math.addExact(notOpenYet, issueClosed)),
                        Math.addExact(stockNotInitialized, metadataNotInitialized));
        long totalRequests = Math.addExact(reserved, failed);

        return new AdminCouponIssueResultCounts(
                couponId,
                totalRequests,
                reserved,
                failed,
                soldOut,
                duplicateIssue,
                notOpenYet,
                issueClosed,
                stockNotInitialized,
                metadataNotInitialized,
                dlqFailed,
                0,
                0);
    }

    /**
     * dlqFailed는 DLQ 복구마저 소진해 최종 실패로 확정된(= 더 이상 DB에 반영될 일이 없는) 건수다.
     * notPersisted에서 이만큼을 빼야 "아직 재시도 중"인 진짜 pending만 남는다 — 안 빼면 관리자가
     * 이미 관리자 개입이 필요하다고 알림까지 간 건을 "곧 끝날 것"으로 오판할 수 있다.
     */
    public AdminCouponIssueResultCounts withPersistenceProgress(long dbPersisted) {
        long notPersisted = reserved > dbPersisted ? reserved - dbPersisted : 0;
        long pendingOrRetrying =
                notPersisted > dlqFailed ? notPersisted - dlqFailed : 0;

        return new AdminCouponIssueResultCounts(
                couponId,
                totalRequests,
                reserved,
                failed,
                soldOut,
                duplicateIssue,
                notOpenYet,
                issueClosed,
                stockNotInitialized,
                metadataNotInitialized,
                dlqFailed,
                dbPersisted,
                pendingOrRetrying);
    }
}
