#!/bin/sh

set -eu

REPOSITORY_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SCRIPT_PATH="${REPOSITORY_ROOT}/scripts/mocou-log-backup.sh"
TEMPORARY_DIRECTORY="$(mktemp -d)"
MOCK_BIN_DIRECTORY="${TEMPORARY_DIRECTORY}/bin"
LOG_DIRECTORY="${TEMPORARY_DIRECTORY}/logs"
AWS_CALL_LOG="${TEMPORARY_DIRECTORY}/aws-calls.log"
LOGGER_CALL_LOG="${TEMPORARY_DIRECTORY}/logger-calls.log"
BACKUP_LOG_FILE="${TEMPORARY_DIRECTORY}/backup.log"

cleanup() {
    rm -rf "${TEMPORARY_DIRECTORY}"
}

trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_file_exists() {
    [ -f "$1" ] || fail "파일이 있어야 합니다: $1"
}

assert_file_not_exists() {
    [ ! -e "$1" ] || fail "파일이 없어야 합니다: $1"
}

assert_contains() {
    grep -F -- "$2" "$1" >/dev/null || fail "다음 내용이 있어야 합니다: $2"
}

mkdir -p "${MOCK_BIN_DIRECTORY}" "${LOG_DIRECTORY}/archive"

cat > "${MOCK_BIN_DIRECTORY}/aws" <<'MOCK_AWS'
#!/bin/sh
set -eu

printf '%s\n' "$*" >> "${AWS_CALL_LOG}"

if [ "${1}" = "s3" ] && [ "${2}" = "cp" ] && [ "${AWS_CP_FAIL:-false}" = "true" ]; then
    exit 1
fi

exit 0
MOCK_AWS

cat > "${MOCK_BIN_DIRECTORY}/logger" <<'MOCK_LOGGER'
#!/bin/sh
set -eu

printf '%s\n' "$*" >> "${LOGGER_CALL_LOG}"
MOCK_LOGGER

cat > "${MOCK_BIN_DIRECTORY}/find" <<'MOCK_FIND'
#!/bin/sh
exit 1
MOCK_FIND

chmod +x "${MOCK_BIN_DIRECTORY}/aws" "${MOCK_BIN_DIRECTORY}/logger" "${MOCK_BIN_DIRECTORY}/find"

active_file="${LOG_DIRECTORY}/system-error.log"
printf 'active error log' > "${active_file}"

uploaded_file="${LOG_DIRECTORY}/archive/system-error.2026-08-20.0.log.gz"
uploaded_marker="${uploaded_file}.s3-uploaded"
printf 'uploaded' > "${uploaded_file}"
touch -t 202001010000 "${uploaded_file}"

PATH="${MOCK_BIN_DIRECTORY}:${PATH}" \
AWS_CALL_LOG="${AWS_CALL_LOG}" \
LOGGER_CALL_LOG="${LOGGER_CALL_LOG}" \
BACKUP_LOG_FILE="${BACKUP_LOG_FILE}" \
LOG_DIR="${LOG_DIRECTORY}" \
AWS_COMMAND="${MOCK_BIN_DIRECTORY}/aws" \
LOGGER_COMMAND="${MOCK_BIN_DIRECTORY}/logger" \
sh "${SCRIPT_PATH}"

assert_file_exists "${uploaded_file}"
assert_file_exists "${uploaded_marker}"
assert_contains "${AWS_CALL_LOG}" "s3 cp ${uploaded_file} s3://mocou-app-logs-2026/prod/system-error/2026/08/20/system-error.2026-08-20.0.log.gz"
assert_contains "${AWS_CALL_LOG}" "s3 cp ${active_file} s3://mocou-app-logs-2026/prod/system-error/active/system-error.log"

touch -t 202001010000 "${uploaded_marker}"

PATH="${MOCK_BIN_DIRECTORY}:${PATH}" \
AWS_CALL_LOG="${AWS_CALL_LOG}" \
LOGGER_CALL_LOG="${LOGGER_CALL_LOG}" \
BACKUP_LOG_FILE="${BACKUP_LOG_FILE}" \
LOG_DIR="${LOG_DIRECTORY}" \
AWS_COMMAND="${MOCK_BIN_DIRECTORY}/aws" \
LOGGER_COMMAND="${MOCK_BIN_DIRECTORY}/logger" \
sh "${SCRIPT_PATH}"

assert_file_not_exists "${uploaded_file}"
assert_file_not_exists "${uploaded_marker}"
assert_contains "${AWS_CALL_LOG}" "s3api head-object --bucket mocou-app-logs-2026 --key prod/system-error/2026/08/20/system-error.2026-08-20.0.log.gz"

failed_file="${LOG_DIRECTORY}/archive/system-error.2026-08-21.0.log.gz"
printf 'failed' > "${failed_file}"
touch -t 202001010000 "${failed_file}"

if PATH="${MOCK_BIN_DIRECTORY}:${PATH}" \
    AWS_CALL_LOG="${AWS_CALL_LOG}" \
    LOGGER_CALL_LOG="${LOGGER_CALL_LOG}" \
    BACKUP_LOG_FILE="${BACKUP_LOG_FILE}" \
    LOG_DIR="${LOG_DIRECTORY}" \
    AWS_COMMAND="${MOCK_BIN_DIRECTORY}/aws" \
    LOGGER_COMMAND="${MOCK_BIN_DIRECTORY}/logger" \
    AWS_CP_FAIL=true \
    sh "${SCRIPT_PATH}"; then
    fail "업로드 실패 시 백업 스크립트는 실패해야 합니다"
fi

assert_file_exists "${failed_file}"
assert_contains "${LOGGER_CALL_LOG}" "S3 upload failed: ${failed_file}"

if PATH="${MOCK_BIN_DIRECTORY}:${PATH}" \
    AWS_CALL_LOG="${AWS_CALL_LOG}" \
    LOGGER_CALL_LOG="${LOGGER_CALL_LOG}" \
    BACKUP_LOG_FILE="${BACKUP_LOG_FILE}" \
    LOG_DIR="${LOG_DIRECTORY}" \
    AWS_COMMAND="${MOCK_BIN_DIRECTORY}/aws" \
    LOGGER_COMMAND="${MOCK_BIN_DIRECTORY}/logger" \
    FIND_COMMAND="${MOCK_BIN_DIRECTORY}/find" \
    sh "${SCRIPT_PATH}"; then
    fail "archive 파일 탐색 실패 시 백업 스크립트는 실패해야 합니다"
fi

assert_contains "${LOGGER_CALL_LOG}" "Archive file discovery failed: ${LOG_DIRECTORY}/archive"

echo "PASS: mocou log backup script"
