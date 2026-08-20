package com.mocou.datagen;

import com.mocou.lifecycle.CouponIssueStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배분 결과를 {@code coupon_issue}와 {@code coupon_issue_history}에 적재한다.
 *
 * <p>회차를 하나씩 배분하고 적재한 뒤 버린다. 300만 건을 모아두면 힙이 감당하지 못하므로, 한 번에 들고 있는 것은 회차 하나분뿐이다.
 *
 * <p>발급과 그 최초 이력은 같은 트랜잭션에서 처리한다. 발급만 들어가고 이력이 빠지면 그 자체로 정합성 위반 데이터가 된다. B2 정책도 발급
 * 트랜잭션에서 {@code coupon_issue} 생성과 최초 이력 기록을 함께 처리하도록 정하고 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IssueGenerator {

    private static final String INSERT_ISSUE_SQL =
            "INSERT INTO coupon_issue "
                    + "(coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_HISTORY_SQL =
            "INSERT INTO coupon_issue_history "
                    + "(coupon_issue_id, from_status, to_status, changed_at, idempotency_key) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final IssueAllocator allocator;

    /**
     * 과거 회차 전체에 발급 이력과 상태 이력을 만든다.
     *
     * @param rounds {@link CouponSeeder}가 만든 회차 목록. 마지막 시연 회차는 제외하고 처리한다
     * @return 적재한 발급 건수
     */
    int generate(List<CouponSeedSpec> rounds, LocalDateTime baseTime, long demoCouponId) {
        LocalDateTime startedAt = LocalDateTime.now();
        int issued = 0;
        long histories = 0;

        for (CouponSeedSpec round : rounds) {
            if (round.couponId() == demoCouponId) {
                continue;
            }
            List<IssueAllocation> allocations =
                    allocator.allocate((int) round.couponId(), round.openAt(), baseTime);
            List<Object[]> historyRows = historyRows(allocations);

            transactionTemplate.executeWithoutResult(
                    status -> {
                        jdbcTemplate.batchUpdate(INSERT_ISSUE_SQL, issueRows(allocations));
                        jdbcTemplate.batchUpdate(INSERT_HISTORY_SQL, historyRows);
                    });

            issued += allocations.size();
            histories += historyRows.size();
            log.debug("{}회차 적재 완료 (발급 {}건)", round.couponId(), allocations.size());
        }

        Duration elapsed = Duration.between(startedAt, LocalDateTime.now());
        log.info("발급 이력 {}건, 상태 이력 {}건 적재 완료 ({}ms)", issued, histories, elapsed.toMillis());
        return issued;
    }

    private List<Object[]> issueRows(List<IssueAllocation> allocations) {
        List<Object[]> rows = new ArrayList<>(allocations.size());
        for (IssueAllocation allocation : allocations) {
            rows.add(
                    new Object[] {
                        allocation.couponIssueId(),
                        allocation.couponId(),
                        allocation.memberId(),
                        allocation.status().name(),
                        Timestamp.valueOf(allocation.issuedAt()),
                        allocation.usedAt() == null ? null : Timestamp.valueOf(allocation.usedAt()),
                        Timestamp.valueOf(allocation.expiresAt())
                    });
        }
        return rows;
    }

    /**
     * 발급 한 건이 상태에 따라 이력 1~2행이 된다.
     *
     * <p>행이 없다가 생기는 것도 한 번의 상태 변화라서 최초 이력은 {@code UNISSUED -> ISSUED}로 남긴다. 최종 상태에
     * 도달한 건은 그 전이가 일어난 시각으로 이력을 하나 더 남긴다.
     */
    private List<Object[]> historyRows(List<IssueAllocation> allocations) {
        List<Object[]> rows = new ArrayList<>(allocations.size() * 2);
        for (IssueAllocation allocation : allocations) {
            long issueId = allocation.couponIssueId();
            rows.add(
                    new Object[] {
                        issueId,
                        CouponIssueStatus.UNISSUED.name(),
                        CouponIssueStatus.ISSUED.name(),
                        Timestamp.valueOf(allocation.issuedAt()),
                        "ISSUE:" + issueId
                    });

            switch (allocation.status()) {
                case USED ->
                        rows.add(
                                new Object[] {
                                    issueId,
                                    CouponIssueStatus.ISSUED.name(),
                                    CouponIssueStatus.USED.name(),
                                    Timestamp.valueOf(allocation.usedAt()),
                                    "USE:" + issueId
                                });
                case EXPIRED ->
                        rows.add(
                                new Object[] {
                                    issueId,
                                    CouponIssueStatus.ISSUED.name(),
                                    CouponIssueStatus.EXPIRED.name(),
                                    Timestamp.valueOf(allocation.expiresAt()),
                                    "EXPIRE:" + issueId + ":" + allocation.expiresAt()
                                });
                case ISSUED -> {
                    // 아직 최종 상태가 아니라 최초 이력 하나로 끝난다
                }
            }
        }
        return rows;
    }
}
