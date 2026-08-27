# 시스템 오류 로그 S3 백업 운영

## 목적과 최종 흐름

기존 JSON 콘솔 로그는 유지하고, `ERROR` 이상 이벤트를 파일에도 JSON 한 줄씩 기록한다. 완료된 archive 파일은 EC2에서 S3로 백업하고, S3와 로컬의 보관 기간을 분리해 관리한다.

```text
Spring Boot ERROR 로그
→ Docker app-logs volume
→ Logback archive(.gz) 롤링
→ root cron (KST 00:10)
→ S3 업로드 및 존재 확인
→ 성공 확인 파일만 로컬 7일 보관 후 삭제
→ S3 Lifecycle으로 90일 후 삭제
```

## 책임 분리

| 구성 요소 | 책임 | 삭제 여부 |
| --- | --- | --- |
| Logback | `ERROR` 이상을 파일에 기록하고 날짜 또는 10MB 기준으로 archive 파일 생성 | archive 파일을 삭제하지 않음 |
| Docker named volume `app-logs` | 컨테이너 교체 후에도 현재 로그와 archive 로그 보존 | `docker compose down -v` 또는 volume 삭제 시에만 삭제됨 |
| root cron + 백업 스크립트 | S3 업로드, 재시도, S3 존재 확인, 로컬 7일 보관 뒤 삭제 | S3 객체가 확인된 파일만 삭제 |
| S3 Lifecycle | 장기 보관 종료 | S3 객체 생성 후 90일에 삭제 |

`src/main/resources/logback-spring.xml`에는 `maxHistory`, `totalSizeCap`을 두지 않는다. 이 설정이 있으면 S3 업로드에 실패한 archive 파일도 Logback이 삭제할 수 있어 백업 정책과 충돌한다.

## 로그 파일 형식과 위치

컨테이너의 작업 경로는 `/app`이고 운영 Compose는 `/app/logs`에 `app-logs` named volume을 마운트한다.

| 구분 | 컨테이너 경로 | 설명 |
| --- | --- | --- |
| 활성 파일 | `/app/logs/system-error.log` | 현재 기록 중인 파일. 매 백업 실행 때 `active/system-error.log` S3 key로 업로드하며 로컬 파일은 삭제하지 않음 |
| archive 파일 | `/app/logs/archive/system-error.YYYY-MM-DD.N.log.gz` | 백업 대상. 하루에 10MB를 넘으면 같은 날짜의 `N`이 증가할 수 있음 |

Logback은 다음 로그 이벤트가 발생하는 시점에 날짜 롤링을 수행할 수 있다. 따라서 백업 스크립트는 "어제 파일 하나"가 아니라 archive 디렉터리에 존재하는 모든 `system-error.*.log.gz` 파일을 매일 재시도한다. 롤링이 늦은 파일도 이후 실행에서 유실 없이 업로드된다.

`LOG_PATH` 환경 변수를 기본값과 다른 위치로 바꾸면 Compose의 `app-logs` mount 대상도 반드시 같은 경로로 바꿔야 한다.

## S3 저장 규칙

버킷은 `mocou-app-logs-2026`이다. archive 파일명에서 추출한 날짜를 이용해 다음 key로 저장한다.

```text
s3://mocou-app-logs-2026/prod/system-error/YYYY/MM/DD/<archive-file-name>
```

예시:

```text
s3://mocou-app-logs-2026/prod/system-error/2026/08/27/system-error.2026-08-27.0.log.gz
```

동일한 로컬 파일은 항상 동일한 S3 key에 업로드한다. 따라서 cron 재실행이나 일시적인 실패 이후의 재시도는 안전하다.

EC2 IAM Role에는 최소한 다음 객체 권한이 필요하다.

| 권한 | 용도 |
| --- | --- |
| `s3:PutObject` | archive 파일 업로드 |
| `s3:GetObject` | `head-object`로 업로드된 객체 존재 확인 |

`head-object`는 실제 EC2에서 JSON 응답으로 확인되어 있다.

## 백업 스크립트

저장소 원본은 `scripts/mocou-log-backup.sh`이며, EC2에는 `/usr/local/sbin/mocou-log-backup.sh`로 설치한다. cron은 GitHub를 조회하지 않고 EC2에 설치된 사본만 실행한다. 저장소에서 스크립트를 변경한 경우에는 배포 절차에서 EC2 사본도 갱신해야 한다.

| 환경 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `S3_BUCKET` | `mocou-app-logs-2026` | 대상 버킷 |
| `S3_PREFIX` | `prod/system-error` | S3 key prefix |
| `APP_CONTAINER` | `mocou-app` | `/app/logs` mount source를 찾을 컨테이너 |
| `BACKUP_LOG_FILE` | `/var/log/mocou-log-backup.log` | 상세 실행 로그 |
| `LOG_DIR` | 비어 있음 | 비어 있으면 컨테이너 inspect로 호스트의 `/app/logs` mount source를 자동 탐색. 로컬 테스트 등에서만 명시적으로 지정 |
| `AWS_COMMAND` | `aws` | AWS CLI 실행 파일. 일반 운영에서는 변경하지 않음 |
| `LOGGER_COMMAND` | `logger` | 시스템 로그 기록 명령. 일반 운영에서는 변경하지 않음 |

스크립트는 cron의 제한된 기본 환경에서도 AWS CLI를 찾도록 `/usr/local/bin`을 포함한 실행 경로를 스스로 설정한다. 따라서 AWS 공식 설치 경로인 `/usr/local/bin/aws`를 cron이 놓치지 않는다.

### 실행 알고리즘

1. `mocou-app` 컨테이너의 `/app/logs` mount source를 확인한다. `LOG_DIR`이 지정된 경우 그 값을 사용한다.
2. 활성 `system-error.log`가 있으면 `active/system-error.log` S3 key로 업로드한다. 이 파일은 삭제하지 않아, 다음 오류 이벤트가 없어 롤링되지 않은 마지막 오류도 S3에 보존한다.
3. `<LOG_DIR>/archive/system-error.*.log.gz` 파일을 모두 찾는다.
4. 파일명 날짜를 `YYYY/MM/DD` S3 key로 변환해 `aws s3 cp`로 업로드한다.
5. 업로드 실패 파일은 로컬에 유지하고 `logger`와 상세 로그에 오류를 남긴다. 다른 파일 처리는 계속하며, 하나라도 실패하면 스크립트는 종료 코드 `1`로 끝난다.
6. 파일별 첫 업로드 성공 시 `<archive-file>.s3-uploaded` marker를 생성한다. 이후 재업로드해도 marker 시각은 바꾸지 않는다.
7. marker 생성 시각이 7일 이상 지난 파일만 삭제 후보로 삼는다. 따라서 S3 장애가 오래 지속된 뒤 복구돼도, 첫 성공 업로드 직후에는 삭제하지 않는다.
8. 후보 파일은 `aws s3api head-object`로 동일 S3 key가 존재하는지 확인한다. 확인 실패 시 archive와 marker를 모두 유지한다.
9. S3 객체 존재가 확인된 archive와 marker만 로컬에서 삭제한다.

이 규칙으로 S3 업로드가 장기간 실패해도 미업로드 파일은 local volume에 남아 이후 실행에서 계속 재시도된다. 반대로 S3 적재가 완료된 파일은 EC2에서 7일의 추가 확인 기간을 거친다.

### 실행 기록과 실패 확인

상세 실행 기록은 `/var/log/mocou-log-backup.log`에 남는다.

```bash
sudo tail -n 100 /var/log/mocou-log-backup.log
```

업로드·S3 존재 확인·로컬 삭제 실패는 시스템 로그에도 `mocou-log-backup` 태그로 남는다.

```bash
sudo journalctl -t mocou-log-backup
```

외부 알림(Slack, 이메일, CloudWatch Alarm)은 이 단계에 포함하지 않는다. 백업 실패 시 스크립트의 종료 코드는 `1`이므로, 나중에 모니터링 도구를 추가할 때 실패 신호로 사용할 수 있다.

## EC2 설치 절차

> 이 절차는 운영 서버에서 수행한다. 저장소 변경만으로 EC2 설치·cron 등록·S3 Lifecycle 적용이 자동 실행되지는 않는다.

### 1. 배포 전 확인

운영 EC2의 `/home/ubuntu/mocou/docker-compose.prod.yml`에 `app-logs:/app/logs` mount와 최상위 `app-logs:` volume 정의가 있어야 한다. 현재 CD는 EC2에 이미 있는 Compose 파일을 사용하므로, 이미지 배포와 별도로 서버 Compose 파일 변경도 반영해야 한다.

배포 후 실제 mount를 확인한다.

```bash
docker inspect mocou-app --format '{{range .Mounts}}{{println .Name .Source "->" .Destination}}{{end}}'
docker exec mocou-app sh -c 'find /app/logs -type f -printf "%TY-%Tm-%Td %TH:%TM %s %p\\n" | sort'
```

### 2. 스크립트 설치 및 갱신

저장소의 최신 원본을 EC2에 반영한 뒤 root 실행용 사본을 설치한다.

```bash
cd /home/ubuntu/mocou
sudo install -D -m 0750 scripts/mocou-log-backup.sh /usr/local/sbin/mocou-log-backup.sh
```

갱신 시에도 같은 `install` 명령을 다시 실행한다. 수동 편집은 EC2 사본과 Git 저장소 원본을 불일치시킬 수 있으므로 피한다.

### 3. root cron 등록

`ubuntu` 사용자가 `sudo`로 root crontab을 등록한다. 별도의 root 로그인 계정은 필요하지 않다.

```bash
sudo crontab -e
```

다음 두 줄을 등록한다.

```cron
CRON_TZ=Asia/Seoul
10 0 * * * /usr/local/sbin/mocou-log-backup.sh
```

등록 결과는 다음으로 확인한다.

```bash
sudo crontab -l
```

### 4. 수동 실행 검증

archive 파일이 하나 이상 있을 때 root 권한으로 스크립트를 실행한다.

```bash
sudo /usr/local/sbin/mocou-log-backup.sh
echo $?
sudo tail -n 100 /var/log/mocou-log-backup.log
```

종료 코드 `0`은 모든 대상 파일의 처리가 정상 완료됐음을 의미한다. `1`이면 실패 파일이 local volume에 남아 있고 다음 cron에서 재시도된다.

S3 객체는 날짜 prefix로 확인한다.

```bash
aws s3 ls s3://mocou-app-logs-2026/prod/system-error/2026/08/27/
```

### 5. 백업 실행 로그 logrotate 권장 설정

`/var/log/mocou-log-backup.log` 자체가 계속 커지는 것을 막기 위해 다음 logrotate 설정을 권장한다.

```bash
sudo tee /etc/logrotate.d/mocou-log-backup >/dev/null <<'EOF'
/var/log/mocou-log-backup.log {
    weekly
    rotate 12
    compress
    missingok
    notifempty
    create 0600 root root
}
EOF
sudo logrotate -d /etc/logrotate.d/mocou-log-backup
```

`-d`는 실제 회전 없이 설정만 점검한다.

## S3 Lifecycle 설정

`mocou-app-logs-2026` 버킷에 아래 규칙을 추가한다.

| 항목 | 값 |
| --- | --- |
| 규칙 이름 | `expire-prod-system-error-logs-after-90-days` |
| 범위 | prefix `prod/system-error/` |
| 동작 | 객체 생성 후 90일에 만료 및 삭제 |
| 스토리지 클래스 전환 | 없음 |

기존 Lifecycle 규칙이 있을 수 있으므로, AWS CLI로 전체 설정을 덮어쓰기 전에 먼저 현재 규칙을 조회한다.

```bash
aws s3api get-bucket-lifecycle-configuration --bucket mocou-app-logs-2026
```

현재 버킷은 `NoSuchLifecycleConfiguration` 응답으로 기존 규칙이 없음을 확인했다. 따라서 최초 적용은 다음 명령으로 한다. 성공하면 출력은 없고 종료 코드는 `0`이다.

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket mocou-app-logs-2026 \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "expire-prod-system-error-logs-after-90-days",
        "Status": "Enabled",
        "Filter": { "Prefix": "prod/system-error/" },
        "Expiration": { "Days": 90 }
      }
    ]
  }'

aws s3api get-bucket-lifecycle-configuration --bucket mocou-app-logs-2026
```

마지막 조회 결과에는 위 `ID`, prefix, `Expiration.Days: 90`이 보여야 한다. 나중에 이 버킷에 다른 Lifecycle 규칙을 추가했다면 `put-bucket-lifecycle-configuration`은 전체 규칙을 교체하므로, 조회한 기존 규칙과 새 규칙을 하나의 `Rules` 목록으로 함께 제출한다.

## 장애 대응

| 증상 | 확인 | 처리 |
| --- | --- | --- |
| 업로드가 실패함 | `journalctl -t mocou-log-backup`, 전용 로그 | IAM Role, 네트워크, 버킷·prefix를 확인. 파일은 삭제되지 않고 다음 날 재시도됨 |
| S3 객체 확인이 실패함 | 전용 로그의 `S3 object confirmation failed` | `s3:GetObject` 권한과 동일 S3 key를 확인. 로컬 파일은 유지됨 |
| archive 파일이 없음 | `/app/logs/archive` 목록 확인 | 오류 이벤트가 없거나 다음 이벤트 전이라 롤링이 아직 발생하지 않았을 수 있음. 다음 cron에서 다시 탐색함 |
| 활성 로그 업로드가 실패함 | `journalctl -t mocou-log-backup`, 전용 로그 | S3 권한·네트워크를 확인. 로컬 활성 파일은 삭제되지 않으며 다음 cron에서 다시 업로드함 |
| 로컬 volume 사용량이 증가함 | `docker system df -v`, archive 목록 | S3 실패 파일은 의도적으로 보존됨. 실패 원인을 해결한 뒤 수동 실행으로 재업로드 |
| cron이 실행되지 않음 | `sudo crontab -l`, `journalctl -u cron` | root crontab과 cron 서비스 상태를 확인 |

## 기록 대상

`SYSTEM_ERROR_FILE`은 root logger에 연결되어 있으므로 애플리케이션과 프레임워크가 남기는 모든 `ERROR` 이상 이벤트가 파일에 기록된다. 대표 사례는 처리되지 않은 예외, Redis 연결 실패, Redis Stream DB 동기화 재시도 소진, 정합성 검증 실패, 알림 큐잉·상태 갱신 DB 실패, 배치·스케줄러·프레임워크 처리 실패다.

재고 소진, 중복 발급, 발급 기간 전·후, 발급 준비 전·종료 후 같은 정상적인 발급 거절은 시스템 오류가 아니다. `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `STOCK_NOT_INITIALIZED`, `METADATA_NOT_INITIALIZED`만으로 오류 파일을 기록하지 않는다.

`issue_failure_log`는 Redis Stream → DB 동기화가 재시도 한도를 초과해 재고 보상까지 수행한 발급 건을 배치 담당자가 확인하는 데이터다. 이 테이블의 스키마와 저장 경로는 변경하지 않는다. 동일 사건의 상세 식별 정보는 이 경로에서 확인하고, 시스템 오류 파일에는 안전한 오류 메타데이터와 스택 프레임만 기록한다.

시스템 오류 파일은 요청 본문, 토큰, 이메일, 원문 예외 메시지, 로그 메시지와 MDC를 기록하지 않는다. 대신 예외 유형과 스택 프레임을 남겨 발생 위치를 파악한다. API 요청 오류는 응답의 `traceId`로 기존 콘솔 로그를 연관해 확인한다.
