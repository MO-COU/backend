#!/usr/bin/env bash
set -euo pipefail

start_expiration_job() {
  local run_key="$1" chunk_size="$2" coupon_id="${3:-}"
  local request_body="{\"runKey\":\"$run_key\",\"chunkSize\":$chunk_size}"
  if [[ -n "$coupon_id" ]]; then
    request_body="{\"runKey\":\"$run_key\",\"chunkSize\":$chunk_size,\"couponId\":$coupon_id}"
  fi
  curl --fail --silent --show-error -X POST "$MANAGEMENT_BASE_URL/internal/perf/expiration-jobs" \
    -H 'Content-Type: application/json' \
    -d "$request_body"
}

get_expiration_job() {
  curl --fail --silent --show-error "$MANAGEMENT_BASE_URL/internal/perf/expiration-jobs/$1"
}
