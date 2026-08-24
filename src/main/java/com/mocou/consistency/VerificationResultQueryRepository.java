package com.mocou.consistency;

import java.util.Optional;

public interface VerificationResultQueryRepository {

    Optional<VerificationResultResponse> findByRunId(long runId);
}
