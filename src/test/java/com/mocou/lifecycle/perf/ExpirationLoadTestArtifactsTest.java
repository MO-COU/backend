package com.mocou.lifecycle.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpirationLoadTestArtifactsTest {

    private static final Path LOAD_TEST_DIR = Path.of("load-test", "expiration");

    @Test
    @DisplayName("정리 SQL은 쿠폰과 회원보다 알림을 먼저 삭제한다")
    void deletesNotificationsBeforeTheirReferencedCouponAndMember() throws IOException {
        // given
        String cleanupSql = Files.readString(LOAD_TEST_DIR.resolve("sql/cleanup.sql"));

        // when
        int notificationDelete = cleanupSql.indexOf("DELETE n FROM notification n");
        int couponIssueDelete = cleanupSql.indexOf("DELETE i FROM coupon_issue i");
        int memberDelete = cleanupSql.indexOf("DELETE FROM member");

        // then
        assertThat(notificationDelete).isGreaterThanOrEqualTo(0);
        assertThat(notificationDelete).isLessThan(couponIssueDelete);
        assertThat(notificationDelete).isLessThan(memberDelete);
    }

    @Test
    @DisplayName("지속 부하 시나리오는 k6 실패와 사용 알림 누락을 PASS로 처리하지 않는다")
    void rejectsSustainedRunsWhenK6OrUsedNotificationsFailVerification() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("wait \"$k6_pid\" || k6_exit=$?");
        assertThat(script).contains("\"$k6_exit\" == \"0\"");
        assertThat(script).contains("used_notification");
    }

    @Test
    @DisplayName("warmup은 청크 비교 결과 표에 기록하지 않는다")
    void excludesWarmupsFromChunkComparisonResults() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("record_result");
        assertThat(script).contains("iteration >= warmups");
    }

    @Test
    @DisplayName("검증 FAIL은 결과 파일만 남기지 않고 스크립트 실패로 전파한다")
    void exitsWithFailureWhenAnyScenarioVerificationFails() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("has_failures=1");
        assertThat(script).contains("(( has_failures == 0 )) || exit 1");
    }

    @Test
    @DisplayName("Batch 대기 실패 시 C 시나리오는 k6와 테스트 데이터를 정리한 뒤 실패한다")
    void cleansUpSustainedRunBeforePropagatingBatchWaitFailure() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("if ! wait_for_completion \"$run_key\" \"$raw_dir/batch-status.json\"; then");
        assertThat(script).contains("kill -INT \"$k6_pid\" 2>/dev/null || true");
        assertThat(script).contains("mysql_file \"$SCRIPT_DIR/sql/cleanup.sql\" \"$run_key\" 0 >/dev/null");
    }
}
