package com.mocou.admin;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.sync.CouponIssueSyncEvent;
import com.mocou.issue.sync.CouponIssueSyncEventParser;
import com.mocou.issue.sync.CouponIssueSyncRepository;
import com.mocou.notification.NotificationRepository;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCouponService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminCouponRepository repository;
    private final AdminCouponRealtimeStockRepository realtimeStockRepository;
    private final RedisAdminCouponIssueResultRepository issueResultRepository;
    private final RedisAdminCouponDlqFailureRepository dlqFailureRepository;
    private final CouponIssueSyncRepository issueSyncRepository;
    private final NotificationRepository notificationRepository;

    public AdminCouponService(
            AdminCouponRepository repository,
            AdminCouponRealtimeStockRepository realtimeStockRepository,
            RedisAdminCouponIssueResultRepository issueResultRepository,
            RedisAdminCouponDlqFailureRepository dlqFailureRepository,
            CouponIssueSyncRepository issueSyncRepository,
            NotificationRepository notificationRepository) {
        this.repository = repository;
        this.realtimeStockRepository = realtimeStockRepository;
        this.issueResultRepository = issueResultRepository;
        this.dlqFailureRepository = dlqFailureRepository;
        this.issueSyncRepository = issueSyncRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminCouponSummary> getCoupons() {
        return repository.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public AdminCouponIssueResultCounts getIssueResultCounts(long couponId) {
        validateCouponId(couponId);
        validateCouponExists(couponId);
        AdminCouponIssueResultCounts counts = issueResultRepository.findCounts(couponId);
        return counts.withPersistenceProgress(repository.countIssues(couponId));
    }

    @Transactional(readOnly = true)
    public AdminCouponNotificationCounts getNotificationCounts(long couponId) {
        validateCouponId(couponId);
        validateCouponExists(couponId);
        return AdminCouponNotificationCounts.of(
                couponId, notificationRepository.countIssueSuccessByCouponId(couponId));
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

    /**
     * DLQ 재시도까지 소진해 최종 실패로 확정된 목록을 Redis(issue-dlq-failed
     * Stream) 기준으로 조회하고, issue_failure_log 기록이 남아 있으면 사유/시각을
     * 덧붙인다 — DB 장애로 로그 기록 자체가 실패했을 수 있어 Redis를 기준으로 삼되
     * DB 쪽 정보는 있으면 보강만 한다.
     */
    @Transactional(readOnly = true)
    public List<AdminCouponDlqFailure> getDlqFailures(long couponId) {
        validateCouponId(couponId);
        validateCouponExists(couponId);

        List<AdminCouponDlqFailure> failures = dlqFailureRepository.findFailures(couponId);
        if (failures.isEmpty()) {
            return failures;
        }

        BinaryOperator<AdminCouponFailureLogEntry> keepLatest =
                (first, second) -> first.occurredAt().isAfter(second.occurredAt()) ? first : second;
        Map<Long, AdminCouponFailureLogEntry> logsByMember =
                repository.findDlqFailureLogs(couponId).stream()
                        .collect(Collectors.toMap(
                                AdminCouponFailureLogEntry::memberId, entry -> entry, keepLatest));

        return failures.stream()
                .map(failure -> failure.withFailureLog(logsByMember.get(failure.memberId())))
                .toList();
    }

    /**
     * DLQ 실패 목록의 항목 하나를 관리자가 수동으로 재시도한다 - {@code saveBatch}를
     * 그대로 재사용해 정상 동기화 경로와 같은 저장/재고 차감/알림 큐잉을 거친다.
     *
     * <p>저장(DB)이 먼저이고 failed 스트림에서의 제거는 그다음이다 — DB가 아직도
     * 안 살아났으면 저장에서 예외가 나 스트림 제거까지 가지 않으므로, 항목은 그대로
     * 남아 다음 재시도를 기다린다.
     */
    @Transactional
    public AdminCouponDlqRetryResult retryDlqFailure(long couponId, String recordId) {
        validateCouponId(couponId);
        validateCouponExists(couponId);

        MapRecord<String, String, String> record =
                dlqFailureRepository
                        .findOne(couponId, recordId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_DLQ_FAILURE_NOT_FOUND));
        CouponIssueSyncEvent event = CouponIssueSyncEventParser.parse(record);

        List<CouponIssueSyncEvent> saved = issueSyncRepository.saveBatch(couponId, List.of(event));
        dlqFailureRepository.delete(couponId, recordId);

        return new AdminCouponDlqRetryResult(couponId, event.memberId(), !saved.isEmpty());
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
