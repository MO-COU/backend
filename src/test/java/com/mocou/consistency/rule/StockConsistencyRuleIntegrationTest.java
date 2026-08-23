package com.mocou.consistency.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.consistency.ConsistencyRule;
import com.mocou.consistency.RuleOutcome;
import com.mocou.consistency.RuleStatus;
import com.mocou.consistency.VerificationContext;
import com.mocou.consistency.VerificationRule;
import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 규모를 줄여 구조만 확인한다. 판정식이 비율이 아니라 항등식이라 규모를 줄여도 그대로 성립한다.
 *
 * <p>쿠폰 3종 × 재고 10, 회원 30명으로 시작한다. 1·2번 쿠폰은 매진, 3번은 발급 이력이 없는 시연 회차를 흉내낸다.
 */
@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            // 만료 배치가 돌면 검사 도중 상태가 바뀌어 판정이 흔들린다. 이 테스트가 만드는 발급 건은
            // 만료 시각이 이미 지나 있어 배치가 깨어나는 순간 EXPIRED로 전환되고 이력까지 쌓인다.
            "mocou.lifecycle.expiration.scheduler-enabled=false"
        })
class StockConsistencyRuleIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 23, 12, 0);
    private static final int STOCK = 10;
    private static final int MEMBER_COUNT = 30;
    private static final long DEMO_COUPON_ID = 3;

    @Autowired private List<ConsistencyRule> rules;

    private VerificationContext context;

    @BeforeEach
    void seedNormalData() {
        context = new VerificationContext(BASE_TIME, 300, 1_000);
        insertMembers();
        insertCoupons();
        insertIssues(1, STOCK);
        insertIssues(2, STOCK);
        // 3번은 시연 회차라 발급 이력이 없다. 재고가 온전히 남는다.
        reconcileStock();
    }

    @Test
    @DisplayName("정상 데이터에서는 세 규칙 모두 위반이 없다")
    void allRulesPassOnCleanData() {
        // when, then
        assertThat(outcomes().values())
                .allSatisfy(
                        outcome -> {
                            assertThat(outcome.status()).isEqualTo(RuleStatus.CHECKED);
                            assertThat(outcome.violationCount()).isZero();
                        });
    }

    /** 발급 이력이 없는 시연 회차가 LEFT JOIN 덕분에 검사 범위에 들어온다. */
    @Test
    @DisplayName("발급 이력이 없는 쿠폰도 재고 규칙의 검사 대상에 포함된다")
    void countsCouponWithoutAnyIssue() {
        // when
        Map<VerificationRule, RuleOutcome> outcomes = outcomes();

        // then - 쿠폰 3종이 모두 검사됐다
        assertThat(outcomes.get(VerificationRule.STOCK_MISMATCH).checkedCount()).isEqualTo(3);
        assertThat(outcomes.get(VerificationRule.OVER_ISSUE).checkedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("재고보다 많이 발급되면 초과 발급으로 검출한다")
    void detectsOverIssue() {
        // given - 1번 쿠폰에 재고를 넘겨 2건 더 넣는다
        long firstInjectedId = nextIssueId();
        insertIssue(1, 21, firstInjectedId);
        insertIssue(1, 22, firstInjectedId + 1);
        reconcileStock();

        // when
        RuleOutcome outcome = outcomes().get(VerificationRule.OVER_ISSUE);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations()).singleElement().satisfies(
                violation -> {
                    assertThat(violation.targetId()).isEqualTo(1);
                    assertThat(violation.detail()).contains("총재고 10", "발급 12");
                });
    }

    /** 재고 역산이 막고 있어 실제로는 나올 수 없다. 규칙이 검출 능력을 갖췄는지 확인하려면 직접 어긋뜨려야 한다. */
    @Test
    @DisplayName("잔여 재고를 어긋뜨리면 재고 불일치로 검출한다")
    void detectsStockMismatch() {
        // given - 발급은 그대로 두고 잔여만 1 늘린다
        jdbcTemplate.update(
                "UPDATE coupon_stock SET remaining_quantity = remaining_quantity + 1 WHERE coupon_id = 2");

        // when
        RuleOutcome outcome = outcomes().get(VerificationRule.STOCK_MISMATCH);

        // then
        assertThat(outcome.violationCount()).isEqualTo(1);
        assertThat(outcome.violations()).singleElement().satisfies(
                violation -> {
                    assertThat(violation.targetId()).isEqualTo(2);
                    assertThat(violation.detail()).contains("총재고 10", "발급 10", "잔여 1");
                });
    }

    /**
     * 유니크 인덱스가 INSERT를 막으므로 제약을 잠시 끄고 주입한다. 규칙이 위반을 검출할 수 있다는 것을 보이지 않으면, 0건 반환은
     * 아무것도 증명하지 못한다.
     */
    @Test
    @DisplayName("같은 회원에게 같은 쿠폰이 두 번 발급되면 중복으로 검출한다")
    void detectsDuplicateIssue() {
        // given
        long firstInjectedId = nextIssueId();
        dropUniqueIndex();
        try {
            insertIssue(1, 1, firstInjectedId); // 1번 회원이 1번 쿠폰을 이미 받았는데 한 번 더
            insertIssue(1, 2, firstInjectedId + 1);

            // when
            RuleOutcome outcome = outcomes().get(VerificationRule.DUPLICATE_ISSUE);

            // then - 조합 2개가 각각 2장씩
            assertThat(outcome.violationCount()).isEqualTo(2);
            assertThat(outcome.violations())
                    .allSatisfy(violation -> assertThat(violation.detail()).isEqualTo("발급 2건"));
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM coupon_issue WHERE coupon_issue_id >= ?", firstInjectedId);
            restoreUniqueIndex();
        }
    }

    /**
     * 유니크 인덱스를 그냥 지우면 {@code ERROR 1553}으로 거부된다. {@code fk_issue_coupon}이 선두 컬럼인
     * {@code coupon_id} 인덱스를 요구하는데, 이 유니크 인덱스가 그 역할을 겸하고 있기 때문이다. 대체 인덱스를 먼저 만들어
     * FK가 기댈 곳을 남긴 뒤에 지운다. V6 마이그레이션에서 같은 순서를 썼다.
     */
    private void dropUniqueIndex() {
        jdbcTemplate.update("ALTER TABLE coupon_issue ADD INDEX idx_tmp_issue_coupon (coupon_id)");
        jdbcTemplate.update("ALTER TABLE coupon_issue DROP INDEX uk_issue_coupon_member");
    }

    /** DDL은 롤백되지 않는다. 컨테이너를 공유하므로 복구하지 않으면 뒤따르는 테스트가 다른 스키마에서 돈다. */
    private void restoreUniqueIndex() {
        jdbcTemplate.update(
                "ALTER TABLE coupon_issue ADD UNIQUE KEY uk_issue_coupon_member (coupon_id, member_id)");
        jdbcTemplate.update("ALTER TABLE coupon_issue DROP INDEX idx_tmp_issue_coupon");
    }

    /** 상한은 상세 목록에만 걸린다. 집계까지 잘리면 전체 위반 규모를 알 수 없게 된다. */
    @Test
    @DisplayName("상세가 상한에 걸려도 위반 건수는 전체 수를 유지한다")
    void keepsTotalCountWhenSamplesAreTruncated() {
        // given - 쿠폰 3종 모두 재고를 어긋뜨리고 상한을 1로 낮춘다
        jdbcTemplate.update("UPDATE coupon_stock SET remaining_quantity = remaining_quantity + 1");
        VerificationContext narrow = new VerificationContext(BASE_TIME, 300, 1);

        // when
        RuleOutcome outcome = ruleOf(VerificationRule.STOCK_MISMATCH).check(jdbcTemplate, narrow);

        // then
        assertThat(outcome.violationCount()).isEqualTo(3);
        assertThat(outcome.violations()).hasSize(1);
        assertThat(outcome.truncated()).isTrue();
    }

    private Map<VerificationRule, RuleOutcome> outcomes() {
        return rules.stream()
                .filter(rule -> targetRules().contains(rule.rule()))
                .collect(
                        Collectors.toMap(
                                ConsistencyRule::rule, rule -> rule.check(jdbcTemplate, context)));
    }

    private List<VerificationRule> targetRules() {
        return List.of(
                VerificationRule.DUPLICATE_ISSUE,
                VerificationRule.OVER_ISSUE,
                VerificationRule.STOCK_MISMATCH);
    }

    private ConsistencyRule ruleOf(VerificationRule target) {
        return rules.stream()
                .filter(rule -> rule.rule() == target)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("규칙 구현이 없다: " + target));
    }

    /**
     * 아직 쓰이지 않은 발급 번호를 DB에 물어본다.
     *
     * <p>번호를 상수로 박아두면 시드 규모를 키웠을 때 다시 겹친다. 실제로 시드가 쓰는 번호(쿠폰번호 × 10 + 순번)와 충돌해
     * 테스트가 깨진 적이 있다.
     */
    private long nextIssueId() {
        Long maxId =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(coupon_issue_id), 0) FROM coupon_issue", Long.class);
        return (maxId == null ? 0 : maxId) + 1;
    }

    private void insertMembers() {
        List<Object[]> rows =
                java.util.stream.IntStream.rangeClosed(1, MEMBER_COUNT)
                        .mapToObj(
                                index ->
                                        new Object[] {
                                            (long) index,
                                            "user%04d@mocou.test".formatted(index),
                                            "회원" + index,
                                            "010-0000-%04d".formatted(index),
                                            Timestamp.valueOf(BASE_TIME.minusYears(1))
                                        })
                        .collect(Collectors.toList());
        jdbcTemplate.batchUpdate(
                "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                rows);
    }

    private void insertCoupons() {
        for (long couponId = 1; couponId <= 3; couponId++) {
            String status = couponId == DEMO_COUPON_ID ? "OPEN" : "CLOSED";
            jdbcTemplate.update(
                    "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    couponId,
                    "테스트 쿠폰 " + couponId,
                    Timestamp.valueOf(BASE_TIME.minusDays(30)),
                    Timestamp.valueOf(BASE_TIME.plusDays(30)),
                    status,
                    Timestamp.valueOf(BASE_TIME.minusDays(30)));
            jdbcTemplate.update(
                    "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                            + " VALUES (?, ?, ?)",
                    couponId,
                    STOCK,
                    STOCK);
        }
    }

    private void insertIssues(long couponId, int count) {
        for (int order = 1; order <= count; order++) {
            insertIssue(couponId, order, couponId * 10 + order);
        }
    }

    private void insertIssue(long couponId, long memberId, long issueId) {
        LocalDateTime issuedAt = BASE_TIME.minusDays(20);
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, ?, ?, 'ISSUED', ?, NULL, ?)",
                issueId,
                couponId,
                memberId,
                Timestamp.valueOf(issuedAt),
                Timestamp.valueOf(issuedAt.plusDays(14)));
    }

    /** 더미데이터 생성과 같은 방식으로 잔여 재고를 역산한다. */
    private void reconcileStock() {
        jdbcTemplate.update(
                "UPDATE coupon_stock s"
                        + " JOIN (SELECT coupon_id, COUNT(*) AS issued FROM coupon_issue GROUP BY coupon_id) t"
                        + "   ON t.coupon_id = s.coupon_id"
                        + " SET s.remaining_quantity = s.total_quantity - t.issued");
    }
}
