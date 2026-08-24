#!/usr/bin/env bash

set -euo pipefail

TARGET="${TARGET:-http://localhost:8080}"
COUPON_ID="${COUPON_ID:-301}"
MODE="${MODE:-smoke}"
VERIFY_DB="${VERIFY_DB:-false}"
VERIFY_REDIS="${VERIFY_REDIS:-false}"
VERIFY_CONSISTENCY="${VERIFY_CONSISTENCY:-false}"
DB_WAIT_TIMEOUT="${DB_WAIT_TIMEOUT:-120}"
CONSISTENCY_WAIT_TIMEOUT="${CONSISTENCY_WAIT_TIMEOUT:-300}"
EXPECTED_NEW_DB_COUNT="${EXPECTED_NEW_DB_COUNT:-}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mocou-mysql}"
MYSQL_DATABASE="${MYSQL_DATABASE:-mocou}"
MYSQL_USER="${MYSQL_USER:-mocou}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-mocou}"
REDIS_CONTAINER="${REDIS_CONTAINER:-mocou-redis}"
RESULT_DIR="${RESULT_DIR:-load-test/results}"
TEST_LABEL="${TEST_LABEL:-$(date +%Y%m%d-%H%M%S)_${MODE}_${COUPON_ID}}"

if [[ ! "${COUPON_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "COUPON_ID는 양의 정수여야 합니다: ${COUPON_ID}" >&2
  exit 1
fi

if [[ -n "${EXPECTED_NEW_DB_COUNT}" && ! "${EXPECTED_NEW_DB_COUNT}" =~ ^[0-9]+$ ]]; then
  echo "EXPECTED_NEW_DB_COUNT는 0 이상의 정수여야 합니다: ${EXPECTED_NEW_DB_COUNT}" >&2
  exit 1
fi

print_result() {
  local label="$1"
  local expected="$2"
  local actual="$3"

  if [[ "${expected}" == "${actual}" ]]; then
    printf "%-42s expected=%-10s actual=%-10s PASS\n" "${label}" "${expected}" "${actual}"
    return 0
  fi

  printf "%-42s expected=%-10s actual=%-10s FAIL\n" "${label}" "${expected}" "${actual}"
  return 1
}

case "${MODE}" in
  smoke)
    TEST_FILE="load-test/smoke-issue.js"
    REQUEST_COUNT="${VUS:-10}"
    REQUEST_MEMBER_START="${MEMBER_ID_START:-1}"
    ;;
  duplicate)
    TEST_FILE="load-test/duplicate-issue.js"
    REQUEST_COUNT="1"
    REQUEST_MEMBER_START="${MEMBER_ID:-999999}"
    ;;
  rush)
    TEST_FILE="load-test/rush-issue.js"
    REQUEST_COUNT="${VUS:-20000}"
    REQUEST_MEMBER_START="${MEMBER_ID_START:-1}"
    ;;
  *)
    echo "지원하지 않는 MODE입니다: ${MODE} (smoke, duplicate, rush 중 선택)" >&2
    exit 1
    ;;
esac

if [[ ! "${REQUEST_COUNT}" =~ ^[1-9][0-9]*$ || ! "${REQUEST_MEMBER_START}" =~ ^[1-9][0-9]*$ ]]; then
  echo "테스트 요청 수와 시작 회원 ID는 양의 정수여야 합니다." >&2
  exit 1
fi

if ! curl --silent --show-error --fail --output /dev/null --connect-timeout 3 \
  "${TARGET}/actuator/health"; then
  echo "백엔드에 연결할 수 없습니다: ${TARGET}" >&2
  echo "애플리케이션과 테스트 데이터를 준비한 뒤 다시 실행해주세요." >&2
  exit 1
fi

if [[ "${VERIFY_DB}" == "true" || "${VERIFY_REDIS}" == "true" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "DB 또는 Redis 검증에는 Docker가 필요합니다." >&2
    exit 1
  fi
fi

if [[ "${VERIFY_CONSISTENCY}" == "true" ]] && ! command -v jq >/dev/null 2>&1; then
  echo "정합성 결과 확인에는 jq가 필요합니다." >&2
  exit 1
fi

if [[ "${MODE}" == "rush" && "${VERIFY_REDIS}" == "true" ]]; then
  redis_stock_before="$(docker exec "${REDIS_CONTAINER}" \
    redis-cli GET "coupon:{${COUPON_ID}}:stock")"

  if [[ -z "${redis_stock_before}" ]]; then
    echo "테스트 쿠폰의 Redis 재고 Key가 없습니다." >&2
    exit 1
  fi

  if [[ -n "${EXPECTED_STOCK:-}" && "${EXPECTED_STOCK}" != "${redis_stock_before}" ]]; then
    echo "EXPECTED_STOCK(${EXPECTED_STOCK})과 Redis 재고(${redis_stock_before})가 다릅니다." >&2
    exit 1
  fi

  EXPECTED_STOCK="${EXPECTED_STOCK:-${redis_stock_before}}"
fi

if [[ "${VERIFY_DB}" == "true" ]]; then
  request_member_end=$((REQUEST_MEMBER_START + REQUEST_COUNT - 1))
  available_member_count="$(docker exec "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -Nse \
    "SELECT COUNT(*) FROM member
      WHERE member_id BETWEEN ${REQUEST_MEMBER_START} AND ${request_member_end};")"
  already_issued_count="$(docker exec "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -Nse \
    "SELECT COUNT(*) FROM coupon_issue
      WHERE coupon_id = ${COUPON_ID}
        AND member_id BETWEEN ${REQUEST_MEMBER_START} AND ${request_member_end};")"

  if [[ "${available_member_count}" != "${REQUEST_COUNT}" ]]; then
    echo "요청할 회원 범위가 DB에 없습니다: ${REQUEST_MEMBER_START}~${request_member_end}" >&2
    echo "필요 ${REQUEST_COUNT}명, 확인 ${available_member_count}명" >&2
    exit 1
  fi

  if [[ "${already_issued_count}" != "0" ]]; then
    echo "요청할 회원 중 이미 발급받은 회원이 ${already_issued_count}명 있습니다." >&2
    echo "테스트 쿠폰을 초기화하거나 다른 회원 범위를 사용해주세요." >&2
    exit 1
  fi
fi

db_count_before=""
expected_db_total=""
if [[ "${VERIFY_DB}" == "true" ]]; then
  db_count_before="$(docker exec "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -Nse \
    "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ${COUPON_ID};")"

  if [[ -n "${EXPECTED_NEW_DB_COUNT}" ]]; then
    expected_db_total=$((db_count_before + EXPECTED_NEW_DB_COUNT))
  fi

  echo "테스트 전 DB 발급 건수: ${db_count_before}"
fi

echo "[1/5] ${MODE} 테스트를 실행합니다. couponId=${COUPON_ID}"
mkdir -p "${RESULT_DIR}"
SUMMARY_FILE="${RESULT_DIR}/${TEST_LABEL}-summary.json"

if command -v k6 >/dev/null 2>&1; then
  k6 run \
    --summary-export "${SUMMARY_FILE}" \
    --tag "test_label=${TEST_LABEL}" \
    -e TARGET="${TARGET}" \
    -e COUPON_ID="${COUPON_ID}" \
    -e VUS="${VUS:-}" \
    -e RAMP_UP="${RAMP_UP:-}" \
    -e MEMBER_ID_START="${MEMBER_ID_START:-}" \
    -e MEMBER_ID="${MEMBER_ID:-}" \
    -e EXPECTED_STOCK="${EXPECTED_STOCK:-}" \
    -e WORKER_VUS="${WORKER_VUS:-}" \
    -e MAX_DURATION="${MAX_DURATION:-}" \
    -e REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-}" \
    "${TEST_FILE}"
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -i \
    -e TARGET="${TARGET/localhost/host.docker.internal}" \
    -e COUPON_ID="${COUPON_ID}" \
    -e VUS="${VUS:-}" \
    -e RAMP_UP="${RAMP_UP:-}" \
    -e MEMBER_ID_START="${MEMBER_ID_START:-}" \
    -e MEMBER_ID="${MEMBER_ID:-}" \
    -e EXPECTED_STOCK="${EXPECTED_STOCK:-}" \
    -e WORKER_VUS="${WORKER_VUS:-}" \
    -e MAX_DURATION="${MAX_DURATION:-}" \
    -e REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-}" \
    -v "$(pwd)/${RESULT_DIR}:/results" \
    grafana/k6 run --summary-export "/results/${TEST_LABEL}-summary.json" \
    --tag "test_label=${TEST_LABEL}" - < "${TEST_FILE}"
else
  echo "k6 또는 Docker가 필요합니다." >&2
  exit 1
fi

echo "k6 요약 결과: ${SUMMARY_FILE}"

if [[ "${VERIFY_DB}" != "true" ]]; then
  echo "[2/5] DB 검증은 건너뜁니다. VERIFY_DB=true로 실행할 수 있습니다."
else
  echo "[2/5] Redis Stream Consumer의 DB 반영 완료를 기다립니다."
  deadline=$((SECONDS + DB_WAIT_TIMEOUT))
  previous_count="-1"
  stable_count=0

  while (( SECONDS < deadline )); do
    current_count="$(docker exec "${MYSQL_CONTAINER}" \
      mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -Nse \
      "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ${COUPON_ID};")"

    if [[ -n "${expected_db_total}" && "${current_count}" == "${expected_db_total}" ]]; then
      stable_count=2
    elif [[ -z "${expected_db_total}" && "${current_count}" == "${previous_count}" && "${current_count}" != "${db_count_before}" ]]; then
      stable_count=$((stable_count + 1))
    else
      stable_count=0
    fi

    if (( stable_count >= 2 )); then
      echo "DB 발급 건수가 ${current_count}건으로 안정화됐습니다."
      break
    fi

    previous_count="${current_count}"
    sleep 2
  done

  if (( stable_count < 2 )); then
    echo "${DB_WAIT_TIMEOUT}초 안에 DB 반영 완료를 확인하지 못했습니다." >&2
    exit 1
  fi

  echo "[3/5] DB 발급 결과를 검증합니다."
  db_verification_result="$(docker exec -i "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" \
    --init-command="SET @coupon_id = ${COUPON_ID}" \
    < load-test/verify-issue-result.sql)"
  printf '%s\n' "${db_verification_result}"

  # SQL은 검증 결과가 FAIL이어도 정상 종료될 수 있으므로 출력값도 확인한다.
  if printf '%s\n' "${db_verification_result}" | grep -q $'\tFAIL$'; then
    echo "DB 발급 결과 검증에 실패했습니다." >&2
    exit 1
  fi
fi

if [[ "${VERIFY_REDIS}" == "true" ]]; then
  echo "[4/5] Redis 재고와 DB 발급 결과를 교차 검증합니다."
  redis_stock="$(docker exec "${REDIS_CONTAINER}" redis-cli GET "coupon:{${COUPON_ID}}:stock")"
  stream_length="$(docker exec "${REDIS_CONTAINER}" redis-cli XLEN "coupon:{${COUPON_ID}}:issue-stream")"
  pending_count="$(docker exec "${REDIS_CONTAINER}" redis-cli XPENDING \
    "coupon:{${COUPON_ID}}:issue-stream" coupon-issue-db-sync | sed -n '1p')"
  db_summary="$(docker exec "${MYSQL_CONTAINER}" \
    mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -Nse \
    "SELECT cs.total_quantity, cs.remaining_quantity, COUNT(ci.coupon_issue_id)
       FROM coupon_stock cs
       LEFT JOIN coupon_issue ci ON ci.coupon_id = cs.coupon_id
      WHERE cs.coupon_id = ${COUPON_ID}
      GROUP BY cs.total_quantity, cs.remaining_quantity;")"

  if [[ -z "${redis_stock}" || -z "${db_summary}" ]]; then
    echo "Redis 재고 Key 또는 DB 쿠폰 재고를 찾지 못했습니다." >&2
    exit 1
  fi

  read -r total_quantity db_remaining_quantity db_issued_count <<< "${db_summary}"
  expected_remaining=$((total_quantity - db_issued_count))

  echo
  echo "========== 재고·발급 교차 검증 =========="
  printf "최초 재고: %s, DB 발급 건수: %s\n" "${total_quantity}" "${db_issued_count}"

  verification_failed=false
  print_result "최초 재고 - DB 발급 건수" "${expected_remaining}" "${db_remaining_quantity}" \
    || verification_failed=true
  print_result "계산된 잔여 재고 = Redis 잔여 재고" "${expected_remaining}" "${redis_stock}" \
    || verification_failed=true
  print_result "DB 잔여 재고 = Redis 잔여 재고" "${db_remaining_quantity}" "${redis_stock}" \
    || verification_failed=true
  print_result "처리되지 않은 Stream 이벤트" "0" "${stream_length}" \
    || verification_failed=true
  print_result "Consumer Pending 이벤트" "0" "${pending_count}" \
    || verification_failed=true
  echo "=========================================="

  if [[ "${verification_failed}" == "true" ]]; then
    echo "Redis와 DB의 발급 결과가 일치하지 않습니다." >&2
    exit 1
  fi

  echo "전체 재고·발급 교차 검증을 통과했습니다."
else
  echo "[4/5] Redis 검증은 건너뜁니다. VERIFY_REDIS=true로 실행할 수 있습니다."
fi

if [[ "${VERIFY_CONSISTENCY}" != "true" ]]; then
  echo "[5/5] 정합성 검증은 건너뜁니다. VERIFY_CONSISTENCY=true로 실행할 수 있습니다."
  exit 0
fi

echo "[5/5] 정합성 검증을 실행하고 결과를 기다립니다."
start_response="$(curl --silent --show-error --fail-with-body \
  --request POST "${TARGET}/api/admin/verifications")"
verification_run_id="$(printf '%s' "${start_response}" | jq -er '.data.runId')"
deadline=$((SECONDS + CONSISTENCY_WAIT_TIMEOUT))

while (( SECONDS < deadline )); do
  result_response="$(curl --silent --show-error --fail-with-body \
    "${TARGET}/api/admin/verifications/${verification_run_id}")"
  verification_status="$(printf '%s' "${result_response}" | jq -er '.data.status')"

  if [[ "${verification_status}" == "COMPLETED" ]]; then
    verdict="$(printf '%s' "${result_response}" | jq -er '.data.verdict')"
    violation_count="$(printf '%s' "${result_response}" | jq -er '.data.violationCount')"
    printf '정합성 검증 완료: runId=%s verdict=%s violationCount=%s\n' \
      "${verification_run_id}" "${verdict}" "${violation_count}"

    if [[ "${verdict}" != "PASS" || "${violation_count}" != "0" ]]; then
      echo "정합성 검증에 실패했습니다." >&2
      exit 1
    fi

    echo "쿠폰 발급 전체 흐름 검증을 통과했습니다."
    exit 0
  fi

  sleep 2
done

echo "${CONSISTENCY_WAIT_TIMEOUT}초 안에 정합성 검증이 끝나지 않았습니다." >&2
exit 1
