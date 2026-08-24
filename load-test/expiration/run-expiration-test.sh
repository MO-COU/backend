#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/batch-control.sh
source "$SCRIPT_DIR/lib/batch-control.sh"

SMOKE_BATCH_COUNT=10
scenario=""; chunk_sizes=""; chunk_size=""; repeats=3; warmups=1; arrival_rate=333; has_failures=0; dry_run=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=true; shift;;
    --scenario) scenario="$2"; shift 2;;
    --chunk-sizes) chunk_sizes="$2"; shift 2;;
    --chunk-size) chunk_size="$2"; shift 2;;
    --repeats) repeats="$2"; shift 2;;
    --warmups) warmups="$2"; shift 2;;
    --arrival-rate) arrival_rate="$2"; shift 2;;
    *) die "unknown option: $1";;
  esac
done

if [[ "$dry_run" == "true" ]]; then
  [[ -z "$scenario" ]] || die "--dry-run cannot be combined with --scenario"
else
  [[ "$scenario" == "compare-chunks" || "$scenario" == "race" || "$scenario" == "smoke" ]] || die "--scenario must be compare-chunks, race, or smoke"
fi
# EC2 전용 테스트 서버의 Compose 호스트에서만 실행한다. DB 포트를 외부에 열지 않는다.
if [[ "$scenario" == "compare-chunks" ]]; then [[ "$chunk_sizes" =~ ^[0-9]+(,[0-9]+)*$ ]] || die "--chunk-sizes is required"; fi
if [[ "$scenario" == "race" ]]; then [[ "$chunk_size" =~ ^[0-9]+$ ]] || die "--chunk-size is required"; fi

preflight
if [[ "$dry_run" == "true" ]]; then
  printf 'DRY_RUN=PASS\n'
  exit 0
fi
validate_chunk_size "$SMOKE_BATCH_COUNT"
if [[ "$scenario" == "compare-chunks" ]]; then
  IFS=',' read -r -a validated_chunks <<< "$chunk_sizes"
  for chunk in "${validated_chunks[@]}"; do validate_chunk_size "$chunk"; done
elif [[ "$scenario" == "race" ]]; then
  validate_chunk_size "$chunk_size"
fi

run_id="$(date +%Y%m%d-%H%M%S)-$scenario"
result_dir="$SCRIPT_DIR/results/$run_id"
mkdir -p "$result_dir/raw"
finalize() {
  local exit_code=$?
  tar -czf "$result_dir/artifacts.tar.gz" -C "$result_dir" raw 2>/dev/null || true
  printf 'RESULT_FILE=%s\nARTIFACT_BUNDLE=%s\n' "$result_dir/result.txt" "$result_dir/artifacts.tar.gz"
  trap - EXIT
  exit "$exit_code"
}
trap finalize EXIT
printf 'scenario=%s\nrepeats=%s\nwarmups=%s\narrival_rate=%s\n' "$scenario" "$repeats" "$warmups" "$arrival_rate" > "$result_dir/result.txt"
printf 'git_sha=%s\n' "$(git -C "$SCRIPT_DIR/../.." rev-parse HEAD)" >> "$result_dir/result.txt"
if [[ "$scenario" == "compare-chunks" ]]; then
  printf '\n[A Batch Only]\nchunk\trepeat\tdurationMs\tissued\texpired\tinvalidHistory\tresult\n' >> "$result_dir/result.txt"
elif [[ "$scenario" == "race" ]]; then
  printf '\n[B 만료 경계 정합성]\nchunk\trepeat\tdurationMs\ttimingDeltaMs\trequests\tdropped\tused\texpired\tissued\tinvalidHistory\tresult\n' >> "$result_dir/result.txt"
fi

wait_for_completion() {
  local run_key="$1" output="$2" deadline=$((SECONDS + 300))
  while (( SECONDS < deadline )); do
    get_expiration_job "$run_key" > "$output"
    local status
    status="$(jq -r '.data.status' "$output")"
    [[ "$status" == "COMPLETED" ]] && return 0
    [[ "$status" == "FAILED" ]] && return 1
    sleep 1
  done
  return 1
}

prepare_batch_data() {
  local run_key="$1" raw_dir="$2" batch_count="$3"
  mysql_file "$SCRIPT_DIR/sql/prepare-batch-only.sql" "$run_key" "$batch_count" > "$raw_dir/prepare.txt" || return 1
  awk '/^[0-9]+$/ { value=$1 } END { print value }' "$raw_dir/prepare.txt" > "$raw_dir/coupon-id.txt"
  [[ -s "$raw_dir/coupon-id.txt" ]] || return 1
}

metric_value() { awk -F= -v key="$2" '$1 == key { print $2 }' "$1"; }

append_batch_result() {
  local section="$1" chunk="$2" repeat="$3" raw_dir="$4"
  local duration issued used expired used_notification invalid invalid_final conflicting used_after_expiry result
  duration="$(jq -r '.data.durationMs // 0' "$raw_dir/batch-status.json")"
  issued="$(metric_value "$raw_dir/consistency.txt" ISSUED)"
  used="$(metric_value "$raw_dir/consistency.txt" USED)"
  expired="$(metric_value "$raw_dir/consistency.txt" EXPIRED)"
  used_notification="$(metric_value "$raw_dir/consistency.txt" USED_NOTIFICATION)"
  invalid="$(metric_value "$raw_dir/consistency.txt" INVALID_HISTORY)"
  invalid_final="$(metric_value "$raw_dir/consistency.txt" INVALID_FINAL_HISTORY)"
  conflicting="$(metric_value "$raw_dir/consistency.txt" CONFLICTING_FINAL_HISTORY)"
  used_after_expiry="$(metric_value "$raw_dir/consistency.txt" USED_AFTER_EXPIRY)"
  result="PASS"
  [[ "$(jq -r '.data.status' "$raw_dir/batch-status.json")" == "COMPLETED" && "$issued" == "0" && "$expired" == "10000" && "$invalid" == "0" && "$invalid_final" == "0" && "$conflicting" == "0" && "$used_after_expiry" == "0" ]] || result="FAIL"
  if [[ "$section" == "A" ]]; then
    [[ "$used" == "0" && "$used_notification" == "0" ]] || result="FAIL"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$chunk" "$repeat" "$duration" "$issued" "$expired" "$invalid" "$result" >> "$result_dir/result.txt"
  else
    local requests dropped timing_delta
    requests="$(jq -r '.metrics.http_reqs.values.count // 0' "$raw_dir/k6-summary.json")"
    dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "$raw_dir/k6-summary.json")"
    timing_delta="$(cat "$raw_dir/timing-delta-ms.txt")"
    [[ $((used + expired)) -eq 10000 && "$used_notification" == "$used" && "$requests" == "10000" && "$dropped" == "0" && "$timing_delta" -le 1000 && "$(cat "$raw_dir/k6-exit.txt")" == "0" ]] || result="FAIL"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$chunk" "$repeat" "$duration" "$timing_delta" "$requests" "$dropped" "$used" "$expired" "$issued" "$invalid" "$result" >> "$result_dir/result.txt"
  fi
  if [[ "$result" == "PASS" ]]; then
    return 0
  fi
  has_failures=1
  return 1
}

run_batch_only() (
  local chunk="$1"
  local repeat="$2"
  local record_result="$3"
  local raw_dir="$result_dir/raw/batch-only-$chunk-$repeat"
  local run_key="${run_id}-a-${chunk}-${repeat}"
  cleanup_batch_only() {
    local exit_code=$? cleanup_status=0
    mysql_file "$SCRIPT_DIR/sql/cleanup.sql" "$run_key" 0 >/dev/null || cleanup_status=$?
    if (( exit_code == 0 && cleanup_status != 0 )); then exit "$cleanup_status"; fi
    exit "$exit_code"
  }
  trap cleanup_batch_only EXIT
  mkdir -p "$raw_dir"
  if ! prepare_batch_data "$run_key" "$raw_dir" 10000; then
    return 1
  fi
  collect_runtime_metrics "$raw_dir" before
  if ! start_expiration_job "$run_key" "$chunk" "$(cat "$raw_dir/coupon-id.txt")" > "$raw_dir/request.json"; then
    return 1
  fi
  if ! wait_for_completion "$run_key" "$raw_dir/batch-status.json"; then
    return 1
  fi
  collect_runtime_metrics "$raw_dir" after
  mysql_verify_coupon "$SCRIPT_DIR/sql/verify-final-state.sql" "$(cat "$raw_dir/coupon-id.txt")" > "$raw_dir/consistency.txt"
  if (( record_result )); then append_batch_result A "$chunk" "$repeat" "$raw_dir"; fi
)

run_race() (
  local repeat="$1"
  local raw_dir="$result_dir/raw/race-$chunk_size-$repeat"
  local run_key="${run_id}-b-${chunk_size}-${repeat}"
  local k6_pid=""
  cleanup_race() {
    local exit_code=$? cleanup_status=0
    if [[ -n "$k6_pid" ]]; then
      kill -INT "$k6_pid" 2>/dev/null || true
      wait "$k6_pid" 2>/dev/null || true
    fi
    mysql_file "$SCRIPT_DIR/sql/cleanup.sql" "$run_key" 0 >/dev/null || cleanup_status=$?
    if (( exit_code == 0 && cleanup_status != 0 )); then exit "$cleanup_status"; fi
    exit "$exit_code"
  }
  trap cleanup_race EXIT
  mkdir -p "$raw_dir"
  mysql_file "$SCRIPT_DIR/sql/prepare-race.sql" "$run_key" 10000 > "$raw_dir/prepare.txt"
  collect_runtime_metrics "$raw_dir" before
  local coupon_id race_time
  IFS=$'\t' read -r coupon_id race_time < "$raw_dir/prepare.txt"
  mysql_exec --skip-column-names -e "SELECT JSON_ARRAYAGG(coupon_issue_id) FROM coupon_issue WHERE coupon_id = $coupon_id" > "$raw_dir/issue-ids.json"
  # T-5초에 k6를 시작한다. 판단은 EC2 host 시간이 아니라 MySQL CURRENT_TIMESTAMP만 사용한다.
  wait_until_mysql_time "$race_time" -5
  TARGET="$APP_BASE_URL" ISSUE_IDS_PATH="$raw_dir/issue-ids.json" k6 run --summary-export "$raw_dir/k6-summary.json" "$SCRIPT_DIR/k6/race.js" > "$raw_dir/k6-output.log" 2>&1 &
  k6_pid=$!
  # T에 perf API를 호출한다. API가 반환한 cutoffAt이 실제 B다.
  wait_until_mysql_time "$race_time" 0
  if ! start_expiration_job "$run_key" "$chunk_size" "$coupon_id" > "$raw_dir/request.json"; then
    return 1
  fi
  if ! wait_for_completion "$run_key" "$raw_dir/batch-status.json"; then
    return 1
  fi
  local cutoff_at
  cutoff_at="$(jq -r '.data.cutoffAt' "$raw_dir/batch-status.json")"
  mysql_exec --skip-column-names -e "SELECT ABS(TIMESTAMPDIFF(MICROSECOND, '$race_time', '$cutoff_at')) DIV 1000" > "$raw_dir/timing-delta-ms.txt"
  local k6_exit=0
  wait "$k6_pid" || k6_exit=$?
  k6_pid=""
  printf '%s\n' "$k6_exit" > "$raw_dir/k6-exit.txt"
  collect_runtime_metrics "$raw_dir" after
  printf 'race_repeat=%s\nrace_time=%s\n' "$repeat" "$race_time" >> "$result_dir/result.txt"
  cat "$raw_dir/batch-status.json" >> "$result_dir/result.txt"
  mysql_verify_coupon "$SCRIPT_DIR/sql/verify-final-state.sql" "$coupon_id" > "$raw_dir/consistency.txt"
  append_batch_result B "$chunk_size" "$repeat" "$raw_dir"
)

run_sustained() (
  local chunk="$1"
  local repeat="$2"
  local raw_dir="$result_dir/raw/sustained-$chunk-$repeat"
  local run_key="${run_id}-c-${chunk}-${repeat}"
  local k6_pid=""
  cleanup_sustained() {
    local exit_code=$? cleanup_status=0
    if [[ -n "$k6_pid" ]]; then
      kill -INT "$k6_pid" 2>/dev/null || true
      wait "$k6_pid" 2>/dev/null || true
    fi
    mysql_file "$SCRIPT_DIR/sql/cleanup.sql" "$run_key" 0 >/dev/null || cleanup_status=$?
    if (( exit_code == 0 && cleanup_status != 0 )); then exit "$cleanup_status"; fi
    exit "$exit_code"
  }
  trap cleanup_sustained EXIT
  mkdir -p "$raw_dir"
  if ! prepare_batch_data "$run_key" "$raw_dir" 10000; then
    return 1
  fi
  collect_runtime_metrics "$raw_dir" before
  local coupon_id api_count
  coupon_id="$(cat "$raw_dir/coupon-id.txt")"
  api_count=$((arrival_rate * 132))
  mysql_file_with_coupon "$SCRIPT_DIR/sql/prepare-sustained-api.sql" "$run_key" "$coupon_id" "$api_count" > "$raw_dir/prepare-api.txt"
  mysql_exec --skip-column-names -e "SELECT JSON_ARRAYAGG(coupon_issue_id) FROM coupon_issue WHERE coupon_id = $coupon_id AND expires_at > CURRENT_TIMESTAMP" > "$raw_dir/issue-ids.json"
  TARGET="$APP_BASE_URL" ISSUE_IDS_PATH="$raw_dir/issue-ids.json" ARRIVAL_RATE="$arrival_rate" k6 run --summary-export "$raw_dir/k6-summary.json" "$SCRIPT_DIR/k6/sustained.js" > "$raw_dir/k6-output.log" 2>&1 &
  k6_pid=$!
  if ! start_expiration_job "$run_key" "$chunk" "$coupon_id" > "$raw_dir/request.json"; then
    return 1
  fi
  if ! wait_for_completion "$run_key" "$raw_dir/batch-status.json"; then
    return 1
  fi
  kill -INT "$k6_pid" 2>/dev/null || true
  local k6_exit=0
  wait "$k6_pid" || k6_exit=$?
  k6_pid=""
  collect_runtime_metrics "$raw_dir" after
  printf 'sustained_chunk=%s\nsustained_repeat=%s\n' "$chunk" "$repeat" >> "$result_dir/result.txt"
  cat "$raw_dir/batch-status.json" >> "$result_dir/result.txt"
  mysql_verify_coupon "$SCRIPT_DIR/sql/verify-final-state.sql" "$coupon_id" > "$raw_dir/consistency.txt"
  local duration p95 p99 dropped api_success api_used issued expired used_notification invalid invalid_final conflicting used_after_expiry result
  duration="$(jq -r '.data.durationMs // 0' "$raw_dir/batch-status.json")"
  p95="$(jq -r '.metrics.http_req_duration.values["p(95)"] // "N/A"' "$raw_dir/k6-summary.json")"
  p99="$(jq -r '.metrics.http_req_duration.values["p(99)"] // "N/A"' "$raw_dir/k6-summary.json")"
  dropped="$(jq -r '.metrics.dropped_iterations.values.count // 0' "$raw_dir/k6-summary.json")"
  api_success="$(jq -r '.metrics.use_success.values.count // 0' "$raw_dir/k6-summary.json")"
  api_used="$(mysql_exec --skip-column-names -e "SELECT COUNT(*) FROM coupon_issue i JOIN member m ON m.member_id = i.member_id WHERE i.coupon_id = $coupon_id AND i.status = 'USED' AND m.email LIKE 'perf-$run_key-api-%@example.invalid'")"
  issued="$(metric_value "$raw_dir/consistency.txt" ISSUED)"
  expired="$(metric_value "$raw_dir/consistency.txt" EXPIRED)"
  used_notification="$(metric_value "$raw_dir/consistency.txt" USED_NOTIFICATION)"
  invalid="$(metric_value "$raw_dir/consistency.txt" INVALID_HISTORY)"
  invalid_final="$(metric_value "$raw_dir/consistency.txt" INVALID_FINAL_HISTORY)"
  conflicting="$(metric_value "$raw_dir/consistency.txt" CONFLICTING_FINAL_HISTORY)"
  used_after_expiry="$(metric_value "$raw_dir/consistency.txt" USED_AFTER_EXPIRY)"
  result="PASS"; [[ "$(jq -r '.data.status' "$raw_dir/batch-status.json")" == "COMPLETED" && "$k6_exit" == "0" && "$dropped" == "0" && "$api_success" == "$api_used" && "$expired" == "10000" && "$used_notification" == "$api_used" && "$invalid" == "0" && "$invalid_final" == "0" && "$conflicting" == "0" && "$used_after_expiry" == "0" ]] || result="FAIL"
  if ! grep -q '^\[C 지속 API 부하\]' "$result_dir/result.txt"; then printf '\n[C 지속 API 부하]\nchunk\trepeat\tdurationMs\tapiP95Ms\tapiP99Ms\tapiSuccess\tapiUsed\tdropped\tresult\n' >> "$result_dir/result.txt"; fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$chunk" "$repeat" "$duration" "$p95" "$p99" "$api_success" "$api_used" "$dropped" "$result" >> "$result_dir/result.txt"
  if [[ "$result" == "PASS" ]]; then
    return 0
  fi
  has_failures=1
  return 1
)

run_smoke() (
  local raw_dir="$result_dir/raw/smoke" run_key="${run_id}-smoke"
  cleanup_smoke() {
    local exit_code=$? cleanup_status=0
    mysql_file "$SCRIPT_DIR/sql/cleanup.sql" "$run_key" 0 >/dev/null || cleanup_status=$?
    if (( exit_code == 0 && cleanup_status != 0 )); then exit "$cleanup_status"; fi
    exit "$exit_code"
  }
  trap cleanup_smoke EXIT
  mkdir -p "$raw_dir"
  if ! prepare_batch_data "$run_key" "$raw_dir" "$SMOKE_BATCH_COUNT"; then
    return 1
  fi
  if ! start_expiration_job "$run_key" "$SMOKE_BATCH_COUNT" "$(cat "$raw_dir/coupon-id.txt")" > "$raw_dir/request.json"; then
    return 1
  fi
  if ! wait_for_completion "$run_key" "$raw_dir/batch-status.json"; then
    return 1
  fi
  if ! mysql_verify_coupon "$SCRIPT_DIR/sql/verify-final-state.sql" "$(cat "$raw_dir/coupon-id.txt")" > "$raw_dir/consistency.txt"; then
    return 1
  fi

  local status issued used expired used_notification invalid invalid_final conflicting used_after_expiry
  status="$(jq -r '.data.status' "$raw_dir/batch-status.json")"
  issued="$(metric_value "$raw_dir/consistency.txt" ISSUED)"
  used="$(metric_value "$raw_dir/consistency.txt" USED)"
  expired="$(metric_value "$raw_dir/consistency.txt" EXPIRED)"
  used_notification="$(metric_value "$raw_dir/consistency.txt" USED_NOTIFICATION)"
  invalid="$(metric_value "$raw_dir/consistency.txt" INVALID_HISTORY)"
  invalid_final="$(metric_value "$raw_dir/consistency.txt" INVALID_FINAL_HISTORY)"
  conflicting="$(metric_value "$raw_dir/consistency.txt" CONFLICTING_FINAL_HISTORY)"
  used_after_expiry="$(metric_value "$raw_dir/consistency.txt" USED_AFTER_EXPIRY)"
  printf '\n[Smoke gate]\nstatus=%s\nissued=%s\nused=%s\nexpired=%s\nusedNotification=%s\ninvalidHistory=%s\n' "$status" "$issued" "$used" "$expired" "$used_notification" "$invalid" >> "$result_dir/result.txt"

  if [[ "$status" == "COMPLETED" && "$issued" == "0" && "$used" == "0" && "$expired" == "$SMOKE_BATCH_COUNT" && "$used_notification" == "0" && "$invalid" == "0" && "$invalid_final" == "0" && "$conflicting" == "0" && "$used_after_expiry" == "0" ]]; then
    printf 'SMOKE=PASS\n' >> "$result_dir/result.txt"
  else
    printf 'SMOKE=FAIL\n' >> "$result_dir/result.txt"
    return 1
  fi
)

if [[ "$scenario" == "smoke" ]]; then
  run_smoke || exit 1
  exit 0
fi

run_smoke || exit 1

if [[ "$scenario" == "compare-chunks" ]]; then
  IFS=',' read -r -a chunks <<< "$chunk_sizes"
  for chunk in "${chunks[@]}"; do
    for ((iteration=0; iteration<warmups+repeats; iteration++)); do run_batch_only "$chunk" "$iteration" "$((iteration >= warmups))"; done
  done
  for chunk in "${chunks[@]}"; do
    for ((iteration=1; iteration<=repeats; iteration++)); do run_sustained "$chunk" "$iteration"; done
  done
else
  for ((iteration=1; iteration<=repeats; iteration++)); do run_race "$iteration"; done
fi

(( has_failures == 0 )) || exit 1
