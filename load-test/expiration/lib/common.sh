#!/usr/bin/env bash
set -euo pipefail

EXPIRATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 이 스크립트는 EC2 전용 테스트 서버의 Compose 호스트에서 실행한다.
# localhost는 개발 PC가 아니라 해당 EC2 호스트가 공개한 app(8080) 포트다.
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-$APP_BASE_URL}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_command() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

mysql_exec() {
  docker compose exec -T mysql sh -c 'exec mysql -umocou -p"$MYSQL_PASSWORD" mocou "$@"' sh "$@"
}

mysql_file() {
  local file="$1" run_key="$2" batch_count="$3" api_count="${4:-0}"
  {
    printf "SET @run_key = '%s'; SET @batch_count = %s; SET @api_count = %s;\n" "$run_key" "$batch_count" "$api_count"
    cat "$file"
  } | docker compose exec -T mysql sh -c 'exec mysql -N -B -umocou -p"$MYSQL_PASSWORD" mocou'
}

mysql_file_with_coupon() {
  local file="$1" run_key="$2" coupon_id="$3" api_count="$4"
  {
    printf "SET @run_key = '%s'; SET @coupon_id = %s; SET @api_count = %s;\n" "$run_key" "$coupon_id" "$api_count"
    cat "$file"
  } | docker compose exec -T mysql sh -c 'exec mysql -N -B -umocou -p"$MYSQL_PASSWORD" mocou'
}

mysql_verify_coupon() {
  local file="$1" coupon_id="$2"
  {
    printf "SET @coupon_id = %s;\n" "$coupon_id"
    cat "$file"
  } | docker compose exec -T mysql sh -c 'exec mysql -N -B -umocou -p"$MYSQL_PASSWORD" mocou'
}

wait_until_mysql_time() {
  local target_time="$1" offset_seconds="$2" deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    local reached
    reached="$(mysql_exec --skip-column-names -e "SELECT CURRENT_TIMESTAMP >= TIMESTAMP('$target_time') + INTERVAL $offset_seconds SECOND")"
    [[ "$reached" == "1" ]] && return 0
    sleep 0.05
  done
  die "MySQL time did not reach target: $target_time offset=$offset_seconds"
}

preflight() {
  require_command curl
  require_command jq
  require_command k6
  require_command tar
  require_command docker
  curl --fail --silent --show-error "$MANAGEMENT_BASE_URL/actuator/health" >/dev/null
  local capabilities
  capabilities="$(curl --fail --silent --show-error "$MANAGEMENT_BASE_URL/internal/perf/expiration-jobs/capabilities")"
  [[ "$(jq -r '.data.controlEnabled' <<<"$capabilities")" == "true" ]] || die "perf control API is disabled"
  [[ "$(jq -r '.data.schedulerEnabled' <<<"$capabilities")" == "false" ]] || die "expiration scheduler must be disabled"
  mysql_exec --skip-column-names -e 'SELECT CURRENT_TIMESTAMP' >/dev/null
}

collect_runtime_metrics() {
  local output_dir="$1" phase="$2"
  curl --fail --silent --show-error "$MANAGEMENT_BASE_URL/actuator/metrics/process.cpu.usage" > "$output_dir/actuator-$phase.json"
  mysql_file "$EXPIRATION_DIR/sql/collect-mysql-metrics.sql" metrics 0 > "$output_dir/mysql-status-$phase.txt"
}
