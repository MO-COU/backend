package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationServiceTest {

    private static final LocalDateTime CUTOFF_AT = LocalDateTime.of(2026, 8, 18, 18, 0);
    private static final CouponExpirationCandidate DUE_ISSUE =
            new CouponExpirationCandidate(42L, LocalDateTime.of(2026, 8, 18, 17, 0));

    @Mock private CouponExpirationRepository repository;

    private CouponExpirationService service;

    @BeforeEach
    void setUp() {
        service = new CouponExpirationService(repository);
    }

    @Test
    void expiresDueIssueAndStoresOneHistory() {
        when(repository.findDueIssues(CUTOFF_AT, 1)).thenReturn(List.of(DUE_ISSUE));
        when(repository.markExpired(DUE_ISSUE.couponIssueId(), CUTOFF_AT)).thenReturn(1);

        int selectedCount = service.expireDueIssues(CUTOFF_AT, 1);

        assertThat(selectedCount).isEqualTo(1);
        verify(repository).saveExpiredHistory(DUE_ISSUE);
    }

    @Test
    void doesNotStoreHistoryWhenConcurrentTransitionWon() {
        when(repository.findDueIssues(CUTOFF_AT, 1)).thenReturn(List.of(DUE_ISSUE));
        when(repository.markExpired(DUE_ISSUE.couponIssueId(), CUTOFF_AT)).thenReturn(0);

        service.expireDueIssues(CUTOFF_AT, 1);

        verify(repository, never()).saveExpiredHistory(DUE_ISSUE);
    }
}
