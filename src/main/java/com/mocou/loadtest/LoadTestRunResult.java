package com.mocou.loadtest;

record LoadTestRunResult(
        int requestedCount,
        int issuedCount,
        int soldOutCount,
        int duplicateCount,
        int errorCount,
        Integer p95Ms) {

    int failedCount() {
        return soldOutCount + duplicateCount + errorCount;
    }
}
