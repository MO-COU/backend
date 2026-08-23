#!/usr/bin/env bash
set -euo pipefail

start_expiration_job() {
  local run_key="$1" chunk_size="$2"
  curl --fail --silent --show-error -X POST "$MANAGEMENT_BASE_URL/internal/perf/expiration-jobs" \
    -H 'Content-Type: application/json' \
    -d "{\"runKey\":\"$run_key\",\"chunkSize\":$chunk_size}"
}

get_expiration_job() {
  curl --fail --silent --show-error "$MANAGEMENT_BASE_URL/internal/perf/expiration-jobs/$1"
}
