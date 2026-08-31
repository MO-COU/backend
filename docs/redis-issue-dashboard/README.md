# Redis 카운터 기반 발급 결과 조회 API

## 목적

#124 API는 Redis Lua가 판정한 쿠폰별 발급 결과의 현재 누적값을 개발자가 빠르게 확인하기 위한 도구다. 별도 관측 인프라 없이 애플리케이션과 Redis만으로 동작한다.

## 데이터 흐름

```text
발급 요청
  → reserve-and-append-event.lua
  → coupon:{couponId}:issue-result-counts Hash를 원자적으로 증가
  → GET /api/admin/coupons/{couponId}/issue-result-counts
  → API 호출자가 현재 누적값 조회
```

## Redis 카운터와 Hash 필드

| 필드 | 의미 | 요청·실패 합계 포함 |
|---|---|---|
| `RESERVED` | 예약 성공 | 전체 요청에 포함 |
| `SOLD_OUT` | 재고 소진 | 실패 및 전체 요청에 포함 |
| `DUPLICATE_ISSUE` | 같은 회원의 중복 요청 | 실패 및 전체 요청에 포함 |
| `NOT_OPEN_YET` | 발급 시작 전 요청 | 실패 및 전체 요청에 포함 |
| `ISSUE_CLOSED` | 발급 종료 후 요청 | 실패 및 전체 요청에 포함 |
| `STOCK_NOT_INITIALIZED` | Redis 재고 미초기화 | 실패 및 전체 요청에 포함 |
| `METADATA_NOT_INITIALIZED` | 발급 기간 메타데이터 미초기화 | 실패 및 전체 요청에 포함 |

누락된 Hash 필드는 0으로 해석한다. 음수·숫자가 아닌 값, 합산 오버플로, Redis 접근 실패는 API의 `SERVICE_UNAVAILABLE` 응답으로 변환한다.

### 언제 증가하는가

이 API는 Redis Hash의 값을 계산하거나 추정하지 않는다. `reserve-and-append-event.lua`가 원자적으로 증가시킨 값을 그대로 읽어 온다. 해당 상황의 요청이 없으면 응답값은 `0`이다.

| 카운터 | 증가 조건 | 기본 k6 시나리오 |
|---|---|---|
| 발급 성공 (`RESERVED`) | 발급 기간 중, 재고가 있고, 해당 회원이 처음 발급을 요청 | 발생 |
| 재고 소진 (`SOLD_OUT`) | 발급 기간 중 Redis 재고가 0 이하인 요청 | 발생 |
| 중복 발급 (`DUPLICATE_ISSUE`) | 이미 `issued-members` Set에 있는 회원의 재요청 | 발생 |
| 발급 시작 전 (`NOT_OPEN_YET`) | Redis 서버 시간이 `openAtEpochSecond`보다 이른 요청 | 기본 스크립트에서는 미발생 |
| 발급 종료 (`ISSUE_CLOSED`) | Redis 서버 시간이 `closeAtEpochSecond`와 같거나 지난 요청 | 기본 스크립트에서는 미발생 |
| 재고 미초기화 (`STOCK_NOT_INITIALIZED`) | `coupon:{couponId}:stock` 키가 없는 상태의 요청 | 정상 초기화된 기본 스크립트에서는 미발생 |
| 메타데이터 미초기화 (`METADATA_NOT_INITIALIZED`) | 재고는 있으나 발급 시작·종료 시각 Hash 필드가 없는 요청 | 정상 초기화된 기본 스크립트에서는 미발생 |

`STOCK_NOT_INITIALIZED`와 `METADATA_NOT_INITIALIZED`는 사용자 입력 오류가 아니라 Redis 발급 준비 누락·키 유실을 감지하는 운영 상태다. 두 경우 발급 API는 안전하게 `COUPON_ISSUE_NOT_READY`(503)로 거절한다.

`dlqFailed`(DLQ 최종 실패 건수)는 이 Hash와 무관한 별도 소스에서 온다. 아래 "DB 동기화 진행"을 참고한다.

## DB 동기화 진행

API는 Redis 발급 결과와 함께 Redis Stream 소비자의 DB 적재 진행도 반환한다.

| 지표 | 응답 필드 | 정의 |
|---|---|---|
| Redis 예약 성공 | `reserved` | Redis Lua가 예약을 수락한 누적 횟수 |
| DB 적재 완료 | `dbPersisted` | 해당 `couponId`의 `coupon_issue` 행 수 |
| DLQ 최종 실패 | `dlqFailed` | `coupon:{couponId}:issue-dlq-failed` Stream 길이(`XLEN`) — DLQ 복구까지 재시도 한도를 넘겨 더 이상 자동 재시도되지 않는 건수 |
| 처리 중 또는 재시도 중 | `pendingOrRetrying` | `max(0, reserved - dbPersisted - dlqFailed)` |

`dlqFailed`는 새 요청의 실패가 아니다. Redis 예약 성공 뒤 Stream 소비자가 DB 반영을 반복 시도하고(메인 재시도 → DLQ 복구 재시도), DLQ 복구마저 재시도 한도를 넘기면 해당 엔트리가 `issue-dlq-failed` Stream으로 옮겨지며 이 값이 늘어난다. 이 시점부터는 자동 재시도가 멈추고 관리자가 `GET /{couponId}/issue-dlq/failed`로 확인해 `POST /{couponId}/issue-dlq/failed/{recordId}/retry`로 수동 재시도하거나 직접 판단해야 한다 — Redis 예약 자체를 자동으로 원복하지는 않는다(예전엔 `compensate-coupon.lua`가 자동으로 원복했으나 그 로직은 제거됐다).

Redis와 DB는 하나의 원자적 스냅샷으로 조회되지 않는다. API 호출 시점에 따라 DB 적재 완료와 Redis 카운터 사이에 짧은 차이가 생길 수 있으며, 그 결과 계산값이 음수가 되는 경우 응답값은 0으로 보정한다.

## API 동작

- API: `GET /api/admin/coupons/{couponId}/issue-result-counts`

존재하지 않는 쿠폰은 404 `COUPON_NOT_FOUND`로 응답한다. Redis 접근 실패나 Hash 값 손상은 503 `SERVICE_UNAVAILABLE`으로 응답한다.

### 요청과 응답

쿠폰 ID는 양의 정수 path variable이며 요청 본문과 query parameter는 없다.

```http
GET /api/admin/coupons/4/issue-result-counts
```

성공하면 현재 Redis 누적값을 공통 응답 봉투로 반환한다. `totalRequests`는 `reserved + failed`이며, `failed`는 `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `STOCK_NOT_INITIALIZED`, `METADATA_NOT_INITIALIZED`의 합이다. `dlqFailed`는 별도 지표다.

```json
{
  "success": true,
  "data": {
    "couponId": 4,
    "totalRequests": 12100,
    "reserved": 10000,
    "failed": 2100,
    "soldOut": 2000,
    "duplicateIssue": 100,
    "notOpenYet": 0,
    "issueClosed": 0,
    "stockNotInitialized": 0,
    "metadataNotInitialized": 0,
    "dlqFailed": 3,
    "dbPersisted": 9980,
    "pendingOrRetrying": 17
  },
  "error": null,
  "traceId": "...",
  "timestamp": "2026-08-25T12:00:00+09:00"
}
```

쿠폰 ID가 0 이하이면 400 `INVALID_INPUT`, 존재하지 않는 쿠폰이면 404 `COUPON_NOT_FOUND`, Redis가 응답하지 않거나 Hash 값이 손상된 경우는 503 `SERVICE_UNAVAILABLE`을 반환한다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COUPON_NOT_FOUND",
    "message": "존재하지 않는 쿠폰입니다"
  },
  "traceId": "...",
  "timestamp": "2026-08-25T12:00:00+09:00"
}
```

Swagger UI에서도 같은 계약을 확인할 수 있다.

```text
http://localhost:8080/swagger-ui/index.html
```

## 로컬 확인

애플리케이션 실행 후 PowerShell에서 현재 누적값을 조회한다.

```powershell
Invoke-RestMethod http://localhost:8080/api/admin/coupons/4/issue-result-counts
```

## 한계

- Redis에는 현재 누적값만 있으므로 과거 시점의 변화나 초당 처리량을 복원할 수 없다.
- API는 단일 쿠폰의 현재 누적값을 확인하는 개발 도구이며 사용자 인증·권한 관리가 포함된 운영 콘솔이 아니다.
- Redis Hash의 보존 기간은 쿠폰 데이터 정리 정책과 함께 별도로 관리해야 한다.
- 장기간 추세, 다중 인스턴스 집계, 경보는 [Prometheus·Grafana 방식](../prometheus-grafana-issue-dashboard/README.md)의 범위다.
