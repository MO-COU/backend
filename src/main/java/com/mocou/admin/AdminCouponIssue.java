package com.mocou.admin;

import com.mocou.global.masking.MaskingUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record AdminCouponIssue(
        long issueId,
        long couponId,
        long memberId,
        String memberName,
        String memberEmail,
        String memberPhone,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt,
        LocalDateTime expiresAt,
        @Schema(description = "선착순 발급 순번", example = "1", nullable = true) Long issueSequence,
        Long remainingAtIssue) {

    public static AdminCouponIssue withMaskedMember(
            long issueId,
            long couponId,
            long memberId,
            String memberName,
            String memberEmail,
            String memberPhone,
            String status,
            LocalDateTime issuedAt,
            LocalDateTime usedAt,
            LocalDateTime expiresAt,
            Long issueSequence,
            Long remainingAtIssue) {
        return new AdminCouponIssue(
                issueId,
                couponId,
                memberId,
                MaskingUtils.maskName(memberName),
                MaskingUtils.maskEmail(memberEmail),
                MaskingUtils.maskPhone(memberPhone),
                status,
                issuedAt,
                usedAt,
                expiresAt,
                issueSequence,
                remainingAtIssue);
    }
}
