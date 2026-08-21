package com.mocou.admin;

import com.mocou.global.masking.MaskingUtils;
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
        LocalDateTime expiresAt) {

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
            LocalDateTime expiresAt) {
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
                expiresAt);
    }
}
