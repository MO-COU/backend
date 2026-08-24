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

    @Test
    @DisplayName("dry-run은 데이터 변경 없이 사전 점검만 수행한다")
    void supportsReadOnlyDryRun() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("--dry-run");
        assertThat(script).contains("DRY_RUN=PASS");
        assertThat(script).contains("if [[ \"$dry_run\" == \"true\" ]]; then");
    }

    @Test
    @DisplayName("본 부하 테스트는 10건 smoke 검증을 통과한 뒤에만 시작한다")
    void runsSmokeGateBeforeLoadScenarios() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("SMOKE_BATCH_COUNT=10");
        assertThat(script).contains("validate_chunk_size \"$SMOKE_BATCH_COUNT\"");
        assertThat(script).contains("run_smoke");
        assertThat(script).contains("SMOKE=PASS");
        assertThat(script).contains("run_smoke || exit 1");
        assertThat(script).contains("start_expiration_job \"$run_key\" \"$SMOKE_BATCH_COUNT\" \"$(cat \"$raw_dir/coupon-id.txt\")\"");
    }

    @Test
    @DisplayName("smoke 데이터 준비 실패도 정리 경로로 반환한다")
    void returnsPreparationFailureSoSmokeCanCleanUp() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("[[ -s \"$raw_dir/coupon-id.txt\" ]] || return 1");
        assertThat(script).contains("if ! prepare_batch_data \"$run_key\" \"$raw_dir\" \"$SMOKE_BATCH_COUNT\"; then");
    }

    @Test
    @DisplayName("실행 중 어느 단계가 실패해도 시나리오별 테스트 데이터와 k6를 정리한다")
    void cleansUpEachScenarioWhenAnyPostPreparationStepFails() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("trap cleanup_batch_only EXIT");
        assertThat(script).contains("trap cleanup_race EXIT");
        assertThat(script).contains("trap cleanup_sustained EXIT");
        assertThat(script).contains("trap cleanup_smoke EXIT");
        assertThat(script).contains("mysql_file \"$SCRIPT_DIR/sql/cleanup.sql\" \"$run_key\" 0 >/dev/null || cleanup_status=$?");
        assertThat(script).contains("k6_pid=\"\"");
    }

    @Test
    @DisplayName("격리된 시나리오에서 기록된 FAIL도 부모 스크립트의 실패 종료 코드에 반영한다")
    void propagatesScenarioVerificationFailuresFromSubshells() throws IOException {
        // given
        String script = Files.readString(LOAD_TEST_DIR.resolve("run-expiration-test.sh"));

        // when, then
        assertThat(script).contains("if [[ \"$result\" == \"PASS\" ]]; then");
        assertThat(script).contains("has_failures=1\n  return 1");
        assertThat(script).contains("do run_batch_only \"$chunk\" \"$iteration\" \"$((iteration >= warmups))\"; done");
        assertThat(script).contains("do run_sustained \"$chunk\" \"$iteration\"; done");
        assertThat(script).contains("do run_race \"$iteration\"; done");
    }

    @Test
    @DisplayName("만료 부하 테스트 준비 SQL은 최신 쿠폰 스키마의 컬럼만 사용한다")
    void preparesCouponsWithoutDroppedDiscountRateColumn() throws IOException {
        // given
        String batchSql = Files.readString(LOAD_TEST_DIR.resolve("sql/prepare-batch-only.sql"));
        String raceSql = Files.readString(LOAD_TEST_DIR.resolve("sql/prepare-race.sql"));

        // when, then
        assertThat(batchSql).doesNotContain("discount_rate");
        assertThat(raceSql).doesNotContain("discount_rate");
        assertThat(batchSql).contains("INSERT INTO coupon (name, open_at, close_at, status)");
        assertThat(raceSql).contains("INSERT INTO coupon (name, open_at, close_at, status)");
    }
}
