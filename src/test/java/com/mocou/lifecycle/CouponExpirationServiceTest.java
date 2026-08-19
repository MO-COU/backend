package com.mocou.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpirationServiceTest {

    private static final LocalDateTime CUTOFF_AT = LocalDateTime.of(2026, 8, 18, 18, 0);
    private static final CouponExpirationCandidate DUE_ISSUE =
            new CouponExpirationCandidate(42L, LocalDateTime.of(2026, 8, 18, 17, 0));

    @Mock private CouponExpirationRepository repository;
    @InjectMocks private CouponExpirationService service;

    @Test
    @DisplayName("만료 대상 쿠폰을 만료 처리하고 이력을 저장한다")
    void expiresDueIssueAndStoresOneHistory() {
        // given
        given(repository.findDueIssues(CUTOFF_AT, 1)).willReturn(List.of(DUE_ISSUE));
        given(repository.markExpired(DUE_ISSUE.couponIssueId(), CUTOFF_AT)).willReturn(1);

        // when
        int selectedCount = service.expireDueIssues(CUTOFF_AT, 1);

        // then
        assertThat(selectedCount).isEqualTo(1);
        verify(repository).saveExpiredHistory(DUE_ISSUE);
    }

    @Test
    @DisplayName("경쟁 전이가 먼저 완료되면 만료 이력을 저장하지 않는다")
    void doesNotStoreHistoryWhenConcurrentTransitionWon() {
        // given
        given(repository.findDueIssues(CUTOFF_AT, 1)).willReturn(List.of(DUE_ISSUE));
        given(repository.markExpired(DUE_ISSUE.couponIssueId(), CUTOFF_AT)).willReturn(0);

        // when
        service.expireDueIssues(CUTOFF_AT, 1);

        // then
        verify(repository, never()).saveExpiredHistory(DUE_ISSUE);
    }
}
