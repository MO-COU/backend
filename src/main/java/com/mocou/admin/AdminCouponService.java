package com.mocou.admin;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCouponService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminCouponRepository repository;
    private final AdminCouponRealtimeStockRepository realtimeStockRepository;
    private final RedisAdminCouponIssueResultRepository issueResultRepository;

    public AdminCouponService(
            AdminCouponRepository repository,
            AdminCouponRealtimeStockRepository realtimeStockRepository,
            RedisAdminCouponIssueResultRepository issueResultRepository) {
        this.repository = repository;
        this.realtimeStockRepository = realtimeStockRepository;
        this.issueResultRepository = issueResultRepository;
    }

    @Transactional(readOnly = true)
    public AdminCouponIssueResultCounts getIssueResultCounts(long couponId) {
        validateCouponId(couponId);
        validateCouponExists(couponId);
        return issueResultRepository.findCounts(couponId);
    }

    @Transactional(readOnly = true)
    public AdminCouponIssuePage getIssues(long couponId, int page, int size) {
        validateRequest(couponId, page, size);
        validateCouponExists(couponId);
        long totalElements = repository.countIssues(couponId);
        int totalPages = calculateTotalPages(totalElements, size);
        long offset = (long) page * size;
        List<AdminCouponIssue> content = repository.findIssues(couponId, size, offset);
        boolean hasNext = page + 1 < totalPages;

        return new AdminCouponIssuePage(
                content, page, size, totalElements, totalPages, hasNext);
    }

    @Transactional(readOnly = true)
    public AdminCouponStock getStock(long couponId) {
        validateCouponId(couponId);
        AdminCouponStock databaseStock =
                repository
                        .findStock(couponId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        int databaseIssuedQuantity = Math.toIntExact(repository.countIssues(couponId));
        try {
            int realtimeRemainingQuantity =
                    realtimeStockRepository
                            .findRemainingQuantity(couponId)
                            .stream()
                            .findFirst()
                            .orElse(databaseStock.remainingQuantity());
            return databaseStock.withIssueProgress(
                    realtimeRemainingQuantity, databaseIssuedQuantity);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "실시간 쿠폰 재고의 정합성이 맞지 않습니다");
        }
    }

    private void validateRequest(long couponId, int page, int size) {
        validateCouponId(couponId);
        if (page < 0 || size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateCouponId(long couponId) {
        if (couponId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateCouponExists(long couponId) {
        if (!repository.existsCoupon(couponId)) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
    }

    private int calculateTotalPages(long totalElements, int size) {
        return (int) ((totalElements + size - 1) / size);
    }
}
