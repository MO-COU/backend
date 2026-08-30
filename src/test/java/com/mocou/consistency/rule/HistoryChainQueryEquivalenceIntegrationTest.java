package com.mocou.consistency.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.mocou.support.MySqlContainerTest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code BROKEN_CHAIN} 판정 쿼리를 바꾸면서 결과가 달라지지 않았는지 대조한다.
 *
 * <p>바꾸기 전 {@code LAG} 쿼리를 이 테스트 안에 <b>대조군</b>으로 남겨둔다. 쓰이지 않는 SQL이 테스트에 있는 것은 의도한
 * 것이다. 300만 건 실측에서 두 쿼리가 모두 0을 돌려줬지만, 그 0은 "위반이 없어서 0"과 "못 잡아서 0"을 구분하지 못한다.
 * 대조군 없이는 같은 결과를 낸다고 말할 수 없다.
 *
 * <p>두 쿼리는 발급 건의 <b>첫 이력</b>을 서로 다른 방식으로 걸러낸다. {@code LAG}는 직전이 없으면 {@code NULL}을
 * 돌려주고 {@code IS NOT NULL}이 그 행을 버리지만, {@code LATERAL}은 안쪽이 빈 결과라 조인 단계에서 행이 빠진다.
 * 도달하는 결과가 같아야 맞지만 방식이 다르므로 경계에서 확인한다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class HistoryChainQueryEquivalenceIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 23, 12, 0);
    private static final LocalDateTime ISSUED_AT = BASE_TIME.minusDays(2);

    /** 만료 배치가 상태를 바꾸면 이력이 늘어 대조가 흔들린다. */
    private static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(2099, 12, 31, 0, 0);

    private static final int DETAIL_LIMIT = 300;

    /** 바꾸기 전 판정 쿼리(대조군). 건수용. */
    private static final String LEGACY_COUNT_SQL =
            """
            WITH chain AS (
                SELECT from_status,
                       LAG(to_status) OVER (
                           PARTITION BY coupon_issue_id
                           ORDER BY changed_at, history_id
                       ) AS prev_to_status
                FROM coupon_issue_history
            )
            SELECT COUNT(*) FROM chain
            WHERE prev_to_status IS NOT NULL AND prev_to_status <> from_status
            """;

    /** 바꾸기 전 판정 쿼리(대조군). 상세용. */
    private static final String LEGACY_DETAIL_SQL =
            """
            WITH chain AS (
                SELECT history_id, from_status,
                       LAG(to_status) OVER (
                           PARTITION BY coupon_issue_id
                           ORDER BY changed_at, history_id
                       ) AS prev_to_status
                FROM coupon_issue_history
            )
            SELECT history_id, prev_to_status, from_status FROM chain
            WHERE prev_to_status IS NOT NULL AND prev_to_status <> from_status
            ORDER BY history_id
            LIMIT :limit
            """;

    /** 바꾸기 전 MISSING_INITIAL 건수 쿼리(대조군). 조인 + GROUP BY + HAVING. */
    private static final String LEGACY_MISSING_INITIAL_COUNT_SQL =
            """
            SELECT COUNT(*) FROM (
                SELECT i.coupon_issue_id
                FROM coupon_issue i
                LEFT JOIN coupon_issue_history h
                       ON h.coupon_issue_id = i.coupon_issue_id
                      AND h.from_status = 'UNISSUED'
                      AND h.to_status = 'ISSUED'
                GROUP BY i.coupon_issue_id
                HAVING COUNT(h.history_id) <> 1
            ) missing_initial
            """;

    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Test
    @DisplayName("정상 이력에서 두 쿼리가 모두 위반을 찾지 않는다")
    void agreesOnCleanChain() {
        // given - 1번은 발급만, 2번은 사용까지
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertIssue(2, 2, "USED", ISSUED_AT.plusHours(3));
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(202, 2, "ISSUED", "USED", ISSUED_AT.plusHours(3));

        // when, then
        assertSameResult().isZero();
    }

    @Test
    @DisplayName("체인이 끊기면 두 쿼리가 같은 이력 줄을 가리킨다")
    void agreesOnBrokenChain() {
        // given - 202의 출발 상태만 어긋나게 둔다
        seedMembersAndCoupon(1);
        insertIssue(1, 1, "USED", ISSUED_AT.plusHours(3));
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "EXPIRED", "USED", ISSUED_AT.plusHours(3));

        // when, then
        assertSameResult().isEqualTo(1);
        assertThat(currentDetailIds()).containsExactly(102L);
    }

    /**
     * 첫 이력만 있는 발급 건이다. {@code LAG}는 {@code NULL}을 걸러내고 {@code LATERAL}은 조인에서 행을 떨어뜨린다.
     * 방식이 다른 두 경로가 같은 답에 도달하는지 보는 지점이다.
     */
    @Test
    @DisplayName("이력이 한 줄뿐인 발급 건을 두 쿼리 모두 위반으로 세지 않는다")
    void agreesWhenPartitionHasOnlyFirstRow() {
        // given - 발급 세 건 모두 최초 이력 한 줄씩
        seedMembersAndCoupon(3);
        for (int i = 1; i <= 3; i++) {
            insertIssue(i, i, "ISSUED", null);
            insertHistory(100L + i, i, "UNISSUED", "ISSUED", ISSUED_AT);
        }

        // when, then
        assertSameResult().isZero();
    }

    /**
     * {@code changed_at}이 같으면 어느 쪽이 직전인지 정렬만으로 정해지지 않는다. 두 쿼리 모두 {@code history_id}로
     * 한 번 더 가르므로 같은 판정이 나와야 한다. 여기가 어긋나면 재현성이 깨진다.
     */
    @Test
    @DisplayName("같은 시각의 이력이 이어질 때 두 쿼리가 같은 순서로 잇는다")
    void agreesOnIdenticalChangedAt() {
        // given - 두 이력의 changed_at이 완전히 같다
        seedMembersAndCoupon(1);
        insertIssue(1, 1, "USED", ISSUED_AT);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "ISSUED", "USED", ISSUED_AT);

        // when, then - history_id 순으로 이으면 체인이 맞는다
        assertSameResult().isZero();
    }

    @Test
    @DisplayName("같은 시각의 이력이 끊겼을 때도 두 쿼리가 같은 줄을 가리킨다")
    void agreesOnIdenticalChangedAtWithBreak() {
        // given
        seedMembersAndCoupon(1);
        insertIssue(1, 1, "USED", ISSUED_AT);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "EXPIRED", "USED", ISSUED_AT);

        // when, then
        assertSameResult().isEqualTo(1);
        assertThat(currentDetailIds()).containsExactly(102L);
    }

    /**
     * 발급 건이 바뀌는 경계다. 1번은 {@code USED}로 끝나고 2번은 {@code UNISSUED}에서 시작하므로, 칸막이가 없으면
     * 이어지지 않는 두 줄을 붙여 위반으로 잡는다.
     */
    @Test
    @DisplayName("다른 발급 건의 이력을 이어붙이지 않는다")
    void agreesAcrossIssueBoundary() {
        // given - 1번 마지막(USED)과 2번 첫 줄(UNISSUED)이 이력 번호로는 이웃이다
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "USED", ISSUED_AT.plusHours(1));
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "ISSUED", "USED", ISSUED_AT.plusHours(1));
        insertIssue(2, 2, "ISSUED", null);
        insertHistory(103, 2, "UNISSUED", "ISSUED", ISSUED_AT.plusHours(2));

        // when, then
        assertSameResult().isZero();
    }

    @Test
    @DisplayName("끊김이 여러 건이면 건수와 목록이 모두 같다")
    void agreesOnMultipleBreaks() {
        // given - 1번과 3번이 각각 끊긴다
        seedMembersAndCoupon(3);
        insertIssue(1, 1, "USED", ISSUED_AT.plusHours(1));
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "EXPIRED", "USED", ISSUED_AT.plusHours(1));

        insertIssue(2, 2, "USED", ISSUED_AT.plusHours(1));
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(202, 2, "ISSUED", "USED", ISSUED_AT.plusHours(1));

        insertIssue(3, 3, "EXPIRED", null);
        insertHistory(301, 3, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(302, 3, "USED", "EXPIRED", ISSUED_AT.plusHours(2));

        // when, then
        assertSameResult().isEqualTo(2);
        assertThat(currentDetailIds()).containsExactly(102L, 302L);
    }

    /** 같은 데이터로 재실행해도 같은 결과가 나와야 한다(NFR-3). 정렬 기준이 흔들리면 여기서 드러난다. */
    @Test
    @DisplayName("같은 데이터를 여러 번 검사해도 결과가 같다")
    void staysDeterministicAcrossRuns() {
        // given - 같은 시각 이력이 섞여 있어 정렬이 흔들리기 쉬운 구성
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "USED", ISSUED_AT);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "EXPIRED", "USED", ISSUED_AT);
        insertIssue(2, 2, "USED", ISSUED_AT);
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(202, 2, "ISSUED", "USED", ISSUED_AT);

        // when, then
        for (int run = 0; run < 3; run++) {
            assertSameResult().isEqualTo(1);
            assertThat(currentDetailIds()).containsExactly(102L);
        }
    }

    // ---------- MISSING_INITIAL_HISTORY 건수 대조 (#207) ----------
    // 건수는 산수식, 상세는 조인식으로 식이 둘이 됐다. 같은 기준임을 원안 건수식과의 대조로 못 박는다.

    @Test
    @DisplayName("최초 이력 건수: 정상 데이터에서 산수식과 원안이 모두 0을 낸다")
    void missingInitialAgreesOnCleanData() {
        // given
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertIssue(2, 2, "USED", ISSUED_AT.plusHours(3));
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(202, 2, "ISSUED", "USED", ISSUED_AT.plusHours(3));

        // when, then
        assertSameMissingInitialCount(0);
    }

    @Test
    @DisplayName("최초 이력 건수: 이력이 아예 없는 발급을 둘 다 1건으로 센다")
    void missingInitialAgreesOnZeroHistory() {
        // given - 2번 발급은 이력이 한 줄도 없다
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertIssue(2, 2, "ISSUED", null);

        // when, then
        assertSameMissingInitialCount(1);
    }

    @Test
    @DisplayName("최초 이력 건수: 최초 전이가 두 건인 발급을 둘 다 1건으로 센다")
    void missingInitialAgreesOnDuplicatedInitial() {
        // given
        seedMembersAndCoupon(1);
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(102, 1, "UNISSUED", "ISSUED", ISSUED_AT.plusMinutes(1));

        // when, then
        assertSameMissingInitialCount(1);
    }

    /** 0건짜리와 2건짜리가 섞여 있어도 산수(전체 − 1건 이상 + 2건 이상)가 합계를 정확히 낸다. */
    @Test
    @DisplayName("최초 이력 건수: 0건과 2건이 섞여도 두 식의 합이 같다")
    void missingInitialAgreesOnMixedViolations() {
        // given - 1번 정상, 2번 이력 없음, 3번 최초 전이 중복
        seedMembersAndCoupon(3);
        insertIssue(1, 1, "ISSUED", null);
        insertHistory(101, 1, "UNISSUED", "ISSUED", ISSUED_AT);
        insertIssue(2, 2, "ISSUED", null);
        insertIssue(3, 3, "ISSUED", null);
        insertHistory(301, 3, "UNISSUED", "ISSUED", ISSUED_AT);
        insertHistory(302, 3, "UNISSUED", "ISSUED", ISSUED_AT.plusMinutes(1));

        // when, then
        assertSameMissingInitialCount(2);
    }

    @Test
    @DisplayName("최초 이력 건수: 같은 데이터를 여러 번 세도 결과가 같다")
    void missingInitialStaysDeterministicAcrossRuns() {
        // given
        seedMembersAndCoupon(2);
        insertIssue(1, 1, "ISSUED", null);
        insertIssue(2, 2, "ISSUED", null);
        insertHistory(201, 2, "UNISSUED", "ISSUED", ISSUED_AT);

        // when, then
        for (int run = 0; run < 3; run++) {
            assertSameMissingInitialCount(1);
        }
    }

    private void assertSameMissingInitialCount(long expected) {
        long legacyCount = count(LEGACY_MISSING_INITIAL_COUNT_SQL);
        long currentCount = count(HistoryChainRule.MISSING_INITIAL_HISTORY.violationCountSql());
        assertThat(currentCount).as("산수식 건수").isEqualTo(legacyCount);
        assertThat(currentCount).isEqualTo(expected);
    }

    /**
     * 두 쿼리의 건수가 같은지 확인하고, 현재 쿼리의 건수를 돌려준다.
     *
     * <p>상세 목록도 함께 대조한다. 건수만 같고 가리키는 줄이 다르면 리포트가 엉뚱한 곳을 지목한다.
     */
    private org.assertj.core.api.AbstractLongAssert<?> assertSameResult() {
        long legacyCount = count(LEGACY_COUNT_SQL);
        long currentCount = count(HistoryChainRule.BROKEN_CHAIN.violationCountSql());
        assertThat(currentCount).as("위반 건수").isEqualTo(legacyCount);
        assertThat(currentDetailIds()).as("위반 이력 목록").isEqualTo(detailIds(LEGACY_DETAIL_SQL));
        return assertThat(currentCount);
    }

    private List<Long> currentDetailIds() {
        return detailIds(HistoryChainRule.BROKEN_CHAIN.violationSql());
    }

    private long count(String sql) {
        Long value = namedJdbcTemplate.queryForObject(sql, Map.of(), Long.class);
        return value == null ? 0 : value;
    }

    /** 상세 쿼리는 여러 컬럼을 돌려주므로 가리키는 이력 번호만 뽑아 비교한다. */
    private List<Long> detailIds(String sql) {
        return namedJdbcTemplate.query(
                sql, Map.of("limit", DETAIL_LIMIT), (rs, rowNum) -> rs.getLong("history_id"));
    }

    private void seedMembersAndCoupon(int memberCount) {
        for (int i = 1; i <= memberCount; i++) {
            jdbcTemplate.update(
                    "INSERT INTO member (member_id, email, name, phone, created_at) VALUES (?, ?, ?, ?, ?)",
                    i,
                    "user%d@mocou.test".formatted(i),
                    "회원" + i,
                    "010-0000-000%d".formatted(i),
                    Timestamp.valueOf(BASE_TIME.minusYears(1)));
        }
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (1, '테스트 쿠폰', ?, ?, 'OPEN', ?)",
                Timestamp.valueOf(BASE_TIME.minusDays(30)),
                Timestamp.valueOf(BASE_TIME.plusDays(30)),
                Timestamp.valueOf(BASE_TIME.minusDays(30)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                        + " VALUES (1, 100, 100 - ?)",
                memberCount);
    }

    private void insertIssue(long issueId, long memberId, String status, LocalDateTime usedAt) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue"
                        + " (coupon_issue_id, coupon_id, member_id, status, issued_at, used_at, expires_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, ?)",
                issueId,
                memberId,
                status,
                Timestamp.valueOf(ISSUED_AT),
                usedAt == null ? null : Timestamp.valueOf(usedAt),
                Timestamp.valueOf(NEVER_EXPIRES));
    }

    private void insertHistory(
            long historyId, long issueId, String fromStatus, String toStatus, LocalDateTime changedAt) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue_history"
                        + " (history_id, coupon_issue_id, from_status, to_status, changed_at, idempotency_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                historyId,
                issueId,
                fromStatus,
                toStatus,
                Timestamp.valueOf(changedAt),
                "%s:%d".formatted(toStatus, historyId));
    }
}
