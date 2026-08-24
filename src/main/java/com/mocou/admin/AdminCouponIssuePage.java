package com.mocou.admin;

import java.util.List;

public record AdminCouponIssuePage(
        List<AdminCouponIssue> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public AdminCouponIssuePage {
        content = List.copyOf(content);
    }
}
