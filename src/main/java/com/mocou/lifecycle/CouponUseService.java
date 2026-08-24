package com.mocou.lifecycle;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponUseService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final CouponUseRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public CouponUseService(
            CouponUseRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponUseResult use(long issueId, String idempotencyKey) {
        validate(issueId, idempotencyKey);

        Optional<CouponIssueStatus> priorTransition =
                repository.findHistoryTargetStatus(issueId, idempotencyKey);
        if (priorTransition.isPresent()) {
            return resolvePriorTransition(issueId, priorTransition.get());
        }

        if (repository.markUsed(issueId) == 1) {
            try {
                repository.saveUsedHistory(issueId, idempotencyKey);
            } catch (DuplicateKeyException exception) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            CouponIssueState issue = usedIssue(issueId);
            // 조건부 갱신의 승자만 이벤트를 발행해 재시도와 동시 요청의 중복 알림을 막는다.
            eventPublisher.publishEvent(
                    new CouponUsedEvent(issue.couponIssueId(), issue.couponId(), issue.memberId()));
            return usedResult(issue);
        }

        Optional<CouponIssueStatus> concurrentTransition =
                repository.findHistoryTargetStatus(issueId, idempotencyKey);
        if (concurrentTransition.isPresent()) {
            return resolvePriorTransition(issueId, concurrentTransition.get());
        }

        CouponIssueState issue =
                repository
                        .findIssue(issueId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        if (issue.status() == CouponIssueStatus.EXPIRED
                || (issue.status() == CouponIssueStatus.ISSUED && issue.expired())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED);
        }
        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }

    private CouponUseResult resolvePriorTransition(long issueId, CouponIssueStatus targetStatus) {
        if (targetStatus != CouponIssueStatus.USED) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return usedResult(usedIssue(issueId));
    }

    private CouponIssueState usedIssue(long issueId) {
        CouponIssueState issue =
                repository
                        .findIssue(issueId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        if (issue.status() != CouponIssueStatus.USED || issue.usedAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return issue;
    }

    private CouponUseResult usedResult(CouponIssueState issue) {
        return new CouponUseResult(issue.couponIssueId(), issue.status(), issue.usedAt());
    }

    private void validate(long issueId, String idempotencyKey) {
        if (issueId <= 0
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
