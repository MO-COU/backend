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
        long compensated) {

    public static AdminCouponIssueResultCounts of(
            long couponId,
            long reserved,
            long soldOut,
            long duplicateIssue,
            long notOpenYet,
            long issueClosed,
            long stockNotInitialized,
            long metadataNotInitialized,
            long compensated) {
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
                compensated);
    }
}
