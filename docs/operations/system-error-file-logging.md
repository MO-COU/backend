# 시스템 오류 파일 로그 운영

## 목적

관리자 화면이 아닌 서버 로그로 시스템 장애를 추적한다. 기존 JSON 콘솔 로그는 유지하고, `ERROR` 이상 이벤트를 파일에도 JSON 한 줄씩 기록한다.

## 파일 위치와 보관

- 현재 로그: `logs/system-error.log`
- 보관 로그: `logs/archive/system-error.YYYY-MM-DD.N.log.gz`
- 순환 기준: 하루 또는 10MB
- 보관 기간: 30일
- 보관 용량 상한: 1GB

Logback은 현재 로그 파일이 없으면 생성하고, 있으면 이어서 기록한다. 기본 경로 `logs`는 애플리케이션의 실행 기준 디렉터리 아래에 생성된다. 환경 변수 `LOG_PATH`를 지정하면 파일 위치를 바꿀 수 있다.

### 운영 Docker 보관

운영 Compose는 기본 경로인 `/app/logs`에 named volume `app-logs`를 마운트한다. 따라서 앱 컨테이너가 새 이미지로 교체되어도 로그 파일과 보관 로그는 유지되며, `docker compose down -v` 또는 `docker volume rm`으로 해당 볼륨을 삭제할 때만 함께 사라진다.

`LOG_PATH`를 기본값과 다른 경로로 지정하면 Compose의 `app-logs` 마운트 대상도 같은 경로로 변경해야 한다. 그렇지 않으면 새 경로의 로그는 컨테이너 교체 시 보존되지 않는다.

현재 CD는 이미 EC2에 있는 `/home/ubuntu/mocou/docker-compose.prod.yml`로 이미지를 교체하며 Compose 파일 자체를 전송하지 않는다. 따라서 이 볼륨 변경을 운영에 반영하려면 `main` 배포 전에 서버의 Compose 파일도 이 변경본으로 갱신해야 한다.

## 기록 대상

`SYSTEM_ERROR_FILE`은 root logger에 연결되어 있으므로 애플리케이션과 프레임워크가 남기는 모든 `ERROR` 이상 이벤트가 파일에 기록된다. 현재 애플리케이션의 대표 사례는 다음과 같다.

- 처리되지 않은 예외(`SYSTEM_ERROR`)
- Redis 연결 실패 등 일시적 서비스 장애(`SERVICE_UNAVAILABLE`)
- Redis Stream에서 DB로 발급을 동기화하다 재시도 한도를 초과한 사건
- 정합성 검증 전체 실행 또는 개별 규칙의 실패
- 쿠폰 사용 후 알림 처리 실패
- 배치·스케줄러 및 프레임워크 처리 실패

재고 소진, 중복 발급, 발급 기간 전·후, 발급 준비 전·종료 후 같은 정상적인 발급 거절은 시스템 오류가 아니다. `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `STOCK_NOT_INITIALIZED`, `METADATA_NOT_INITIALIZED` 결과는 Redis 발급 결과 카운터에서 집계하며, 이 사유만으로 오류 파일에 기록하지 않는다.

## `issue_failure_log`와의 구분

`issue_failure_log`는 Redis Stream → DB 동기화가 재시도 한도를 초과해 재고 보상까지 수행한 발급 건을 배치 담당자가 확인하는 데이터다. 이 테이블의 스키마와 저장 경로는 변경하지 않는다.

동일한 재시도 소진 사건은 시스템 오류 파일에도 남긴다. 파일 로그에는 `eventId`, Stream ID, `couponId`, `memberId`, 재시도 한도, 재고 보상 결과를 기록해 운영자가 원인을 분석할 수 있게 한다.

## 확인 방법

애플리케이션 실행 기준 디렉터리에서 다음 명령으로 최근 오류를 확인한다.

```bash
tail -n 200 logs/system-error.log
```

Docker 운영 환경에서는 컨테이너 안에서 같은 경로를 확인한다.

```bash
docker compose -f docker-compose.prod.yml exec app tail -n 200 /app/logs/system-error.log
```

파일 생성은 `logs` 디렉터리를 제거한 뒤 `ERROR` 이벤트를 한 건 발생시켜 검증한다. 디렉터리와 `system-error.log`가 다시 생성되고, 한 줄당 하나의 JSON 이벤트가 기록되어야 한다.

애플리케이션의 시스템 오류 핸들러는 요청 본문, 토큰, 이메일, 원문 예외 메시지를 기록하지 않는다. 대신 예외 유형 체인과 해당 예외의 스택 프레임을 남겨 발생 위치를 파악한다. API 요청 오류는 응답의 `traceId`로 콘솔 및 오류 파일 로그를 연관해 확인한다.
