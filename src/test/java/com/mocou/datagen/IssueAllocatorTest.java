package com.mocou.datagen;

import com.mocou.lifecycle.CouponIssueStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueAllocatorTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 19, 12, 0);
    private static final int MEMBER_COUNT = 100_000;
    private static final int ROUND_STOCK = 1_000;

    private final IssueAllocator allocator = new IssueAllocator(properties(MEMBER_COUNT, ROUND_STOCK));

    @Test
    @DisplayName("회차 안에서 같은 회원이 두 번 당첨되지 않는다")
    void neverPicksTheSameMemberTwiceInOneRound() {
        // given
        LocalDateTime openAt = BASE_TIME.minusWeeks(30);

        // when
        List<IssueAllocation> allocations = allocator.allocate(30, openAt, BASE_TIME);

        // then
        Set<Long> members =
                allocations.stream().map(IssueAllocation::memberId).collect(Collectors.toSet());
        assertThat(members).hasSize(ROUND_STOCK);
        assertThat(members).allMatch(id -> id >= 1 && id <= MEMBER_COUNT);
    }

    @Test
    @DisplayName("회차가 다르면 당첨자 명단도 달라진다")
    void differentRoundsPickDifferentWinners() {
        // given
        LocalDateTime openAt = BASE_TIME.minusWeeks(30);

        // when
        Set<Long> first =
                allocator.allocate(10, openAt, BASE_TIME).stream()
                        .map(IssueAllocation::memberId)
                        .collect(Collectors.toSet());
        Set<Long> second =
                allocator.allocate(11, openAt.plusWeeks(1), BASE_TIME).stream()
                        .map(IssueAllocation::memberId)
                        .collect(Collectors.toSet());

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("유효기간이 지난 회차에는 ISSUED가 없다")
    void settledRoundHasNoIssuedState() {
        // given - 30주 전 회차. 유효기간 14일이 훨씬 지났다
        LocalDateTime openAt = BASE_TIME.minusWeeks(30);

        // when
        List<IssueAllocation> allocations = allocator.allocate(30, openAt, BASE_TIME);

        // then
        assertThat(allocations).noneMatch(a -> a.status() == CouponIssueStatus.ISSUED);
        assertThat(countOf(allocations, CouponIssueStatus.USED)).isEqualTo(ROUND_STOCK * 60 / 100);
        assertThat(countOf(allocations, CouponIssueStatus.EXPIRED)).isEqualTo(ROUND_STOCK * 40 / 100);
    }

    @Test
    @DisplayName("유효기간이 남은 회차에는 EXPIRED가 없다")
    void ongoingRoundHasNoExpiredState() {
        // given - 3일 전 회차. 아직 유효기간 안이다
        LocalDateTime openAt = BASE_TIME.minusDays(3);

        // when
        List<IssueAllocation> allocations = allocator.allocate(300, openAt, BASE_TIME);

        // then
        assertThat(allocations).noneMatch(a -> a.status() == CouponIssueStatus.EXPIRED);
        assertThat(countOf(allocations, CouponIssueStatus.USED)).isEqualTo(ROUND_STOCK * 30 / 100);
        assertThat(countOf(allocations, CouponIssueStatus.ISSUED)).isEqualTo(ROUND_STOCK * 70 / 100);
    }

    /** 정합성 검증의 STATE_TIMESTAMP_MISMATCH 규칙이 검사하는 항목과 같다. */
    @Test
    @DisplayName("상태와 시각이 서로 모순되지 않는다")
    void stateAndTimestampsNeverContradict() {
        // when
        List<IssueAllocation> settled = allocator.allocate(30, BASE_TIME.minusWeeks(30), BASE_TIME);
        List<IssueAllocation> ongoing = allocator.allocate(300, BASE_TIME.minusDays(3), BASE_TIME);

        // then
        assertThat(settled).allSatisfy(this::assertConsistent);
        assertThat(ongoing).allSatisfy(this::assertConsistent);
    }

    @Test
    @DisplayName("발급도 사용도 기준 시각을 넘지 않는다")
    void neverRecordsAnythingAfterBaseTime() {
        // given - 오픈 직후에 만들어 남은 시간이 5분도 안 되는 경우
        LocalDateTime openAt = BASE_TIME.minusMinutes(1);

        // when
        List<IssueAllocation> allocations = allocator.allocate(300, openAt, BASE_TIME);

        // then
        assertThat(allocations)
                .allSatisfy(
                        allocation -> {
                            assertThat(allocation.issuedAt()).isBeforeOrEqualTo(BASE_TIME);
                            if (allocation.usedAt() != null) {
                                assertThat(allocation.usedAt()).isBeforeOrEqualTo(BASE_TIME);
                            }
                        });
    }

    @Test
    @DisplayName("같은 회차를 다시 배분하면 완전히 같은 결과가 나온다")
    void reallocatingSameRoundProducesIdenticalResult() {
        // given
        LocalDateTime openAt = BASE_TIME.minusWeeks(30);

        // when
        List<IssueAllocation> first = allocator.allocate(30, openAt, BASE_TIME);
        List<IssueAllocation> second = allocator.allocate(30, openAt, BASE_TIME);

        // then
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("발급 번호는 회차와 순번으로 정해져 회차끼리 겹치지 않는다")
    void issueIdsAreDerivedFromRoundAndOrder() {
        // when
        List<IssueAllocation> round1 = allocator.allocate(1, BASE_TIME.minusWeeks(30), BASE_TIME);
        List<IssueAllocation> round2 = allocator.allocate(2, BASE_TIME.minusWeeks(29), BASE_TIME);

        // then
        assertThat(round1.get(0).couponIssueId()).isEqualTo(1);
        assertThat(round1.get(ROUND_STOCK - 1).couponIssueId()).isEqualTo(ROUND_STOCK);
        assertThat(round2.get(0).couponIssueId()).isEqualTo(ROUND_STOCK + 1);
    }

    @Test
    @DisplayName("재고가 회원 수보다 많으면 배분을 시작하지 않는다")
    void refusesWhenStockExceedsMemberCount() {
        // given
        IssueAllocator tooSmall = new IssueAllocator(properties(500, ROUND_STOCK));

        // when, then
        assertThatThrownBy(() -> tooSmall.allocate(1, BASE_TIME.minusWeeks(30), BASE_TIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1인 1매");
    }

    /**
     * 회원 수가 보폭(618,041)의 배수면 한 회차의 모든 순번이 같은 회원으로 계산된다. 재고보다 회원이 많아 기존 검사는 통과하므로,
     * 서로소 검사가 없으면 적재 도중 UNIQUE 위반으로 터진다.
     */
    @Test
    @DisplayName("회원 수가 보폭과 서로소가 아니면 배분을 시작하지 않는다")
    void refusesWhenMemberCountSharesFactorWithStride() {
        // given - 보폭과 같은 값이라 최대공약수가 618,041이 된다
        IssueAllocator notCoprime = new IssueAllocator(properties(618_041, ROUND_STOCK));

        // when, then
        assertThatThrownBy(() -> notCoprime.allocate(1, BASE_TIME.minusWeeks(30), BASE_TIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("서로소");
    }

    @Test
    @DisplayName("당첨자가 회원 전체에 흩어진다")
    void winnersSpreadAcrossWholeMemberRange() {
        // given
        LocalDateTime openAt = BASE_TIME.minusWeeks(30);

        // when
        List<Long> members =
                allocator.allocate(30, openAt, BASE_TIME).stream()
                        .map(IssueAllocation::memberId)
                        .sorted()
                        .toList();

        // then - 재고가 회원의 1%뿐이라도 명단이 특정 구간에 몰리지 않는다
        long span = members.get(members.size() - 1) - members.get(0);
        assertThat(span).isGreaterThan(MEMBER_COUNT * 9L / 10);
    }

    private void assertConsistent(IssueAllocation allocation) {
        assertThat(allocation.expiresAt()).isAfter(allocation.issuedAt());
        switch (allocation.status()) {
            case USED -> {
                assertThat(allocation.usedAt()).isNotNull();
                assertThat(allocation.usedAt()).isBetween(allocation.issuedAt(), allocation.expiresAt());
            }
            case ISSUED -> {
                assertThat(allocation.usedAt()).isNull();
                assertThat(allocation.expiresAt()).isAfter(BASE_TIME);
            }
            case EXPIRED -> {
                assertThat(allocation.usedAt()).isNull();
                assertThat(allocation.expiresAt()).isBeforeOrEqualTo(BASE_TIME);
            }
        }
    }

    private long countOf(List<IssueAllocation> allocations, CouponIssueStatus status) {
        return allocations.stream().filter(a -> a.status() == status).count();
    }

    private DatagenProperties properties(int memberCount, int roundStock) {
        return new DatagenProperties(memberCount, 300, roundStock, 10_000, null, 20260819L, 10_000);
    }
}
