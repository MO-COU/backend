package com.mocou.consistency;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationResultQueryService {

    private final VerificationResultQueryRepository repository;

    public VerificationResultQueryService(VerificationResultQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public VerificationResultResponse getResult(long runId) {
        if (runId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return repository
                .findByRunId(runId)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.VERIFICATION_RUN_NOT_FOUND));
    }
}
