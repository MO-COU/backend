#!/bin/sh

set -u

PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:${PATH}}"
export PATH

S3_BUCKET="${S3_BUCKET:-mocou-app-logs-2026}"
S3_PREFIX="${S3_PREFIX:-prod/system-error}"
APP_CONTAINER="${APP_CONTAINER:-mocou-app}"
BACKUP_LOG_FILE="${BACKUP_LOG_FILE:-/var/log/mocou-log-backup.log}"
LOG_DIR="${LOG_DIR:-}"
AWS_COMMAND="${AWS_COMMAND:-aws}"
LOGGER_COMMAND="${LOGGER_COMMAND:-logger}"
FAILED=false

umask 077
mkdir -p "$(dirname "${BACKUP_LOG_FILE}")"

log() {
    level="$1"
    message="$2"
    printf '%s [%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" "${level}" "${message}" \
        >> "${BACKUP_LOG_FILE}"
}

record_failure() {
    message="$1"
    log "ERROR" "${message}"
    "${LOGGER_COMMAND}" -t mocou-log-backup -p user.err -- "${message}" || true
    FAILED=true
}

resolve_log_dir() {
    if [ -n "${LOG_DIR}" ]; then
        return 0
    fi

    LOG_DIR="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/logs"}}{{.Source}}{{end}}{{end}}' "${APP_CONTAINER}")"
    if [ -z "${LOG_DIR}" ]; then
        record_failure "Cannot find /app/logs mount source from container: ${APP_CONTAINER}"
        return 1
    fi
}

is_deletion_candidate() {
    find "$1" -type f -mtime +6 -print | grep -q .
}

backup_file() {
    file_path="$1"
    file_name="$(basename "${file_path}")"
    log_date="$(printf '%s' "${file_name}" | sed -n 's/^system-error\.\([0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]\)\.[0-9][0-9]*\.log\.gz$/\1/p')"

    if [ -z "${log_date}" ]; then
        record_failure "Unsupported archive file name: ${file_path}"
        return
    fi

    year="${log_date%%-*}"
    month_day="${log_date#*-}"
    month="${month_day%%-*}"
    day="${month_day#*-}"
    s3_key="${S3_PREFIX}/${year}/${month}/${day}/${file_name}"
    upload_marker="${file_path}.s3-uploaded"

    if ! "${AWS_COMMAND}" s3 cp "${file_path}" "s3://${S3_BUCKET}/${s3_key}" --only-show-errors; then
        record_failure "S3 upload failed: ${file_path}"
        return
    fi

    log "INFO" "S3 upload completed: ${file_path} -> s3://${S3_BUCKET}/${s3_key}"

    if [ ! -e "${upload_marker}" ] && ! touch "${upload_marker}"; then
        record_failure "Upload success marker creation failed: ${upload_marker}"
        return
    fi

    if [ ! -f "${upload_marker}" ]; then
        record_failure "Upload success marker is not a regular file: ${upload_marker}"
        return
    fi

    if ! is_deletion_candidate "${upload_marker}"; then
        return
    fi

    if ! "${AWS_COMMAND}" s3api head-object --bucket "${S3_BUCKET}" --key "${s3_key}" >/dev/null; then
        record_failure "S3 object confirmation failed; local file retained: ${file_path}"
        return
    fi

    if ! rm -f -- "${upload_marker}"; then
        record_failure "Upload success marker deletion failed: ${upload_marker}"
        return
    fi

    if ! rm -f -- "${file_path}"; then
        record_failure "Local archive deletion failed: ${file_path}"
        return
    fi

    log "INFO" "Local archive deleted after seven-day retention: ${file_path}"
}

resolve_log_dir || exit 1

ARCHIVE_DIR="${LOG_DIR}/archive"
if [ ! -d "${ARCHIVE_DIR}" ]; then
    log "INFO" "Archive directory does not exist; nothing to back up: ${ARCHIVE_DIR}"
    exit 0
fi

log "INFO" "S3 log backup started: ${ARCHIVE_DIR}"

FILE_LIST="$(mktemp)"
trap 'rm -f "${FILE_LIST}"' EXIT INT TERM
find "${ARCHIVE_DIR}" -type f -name 'system-error.*.log.gz' -print > "${FILE_LIST}"

while IFS= read -r file_path; do
    [ -n "${file_path}" ] || continue
    backup_file "${file_path}"
done < "${FILE_LIST}"

if [ "${FAILED}" = true ]; then
    log "ERROR" "S3 log backup finished with failures"
    exit 1
fi

log "INFO" "S3 log backup completed"
