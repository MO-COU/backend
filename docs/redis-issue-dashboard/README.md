# Redis 카운터 기반 발급 현황 대시보드

## 목적

#124 대시보드는 Redis Lua가 판정한 쿠폰별 발급 결과의 현재 누적값을 개발자가 빠르게 확인하기 위한 도구다. 별도 관측 인프라 없이 애플리케이션과 Redis만으로 동작하며, 부하 테스트 중 카운터가 증가하는 모습을 5초 간격으로 보여준다.

## 데이터 흐름

```text
발급 요청
  → reserve-and-append-event.lua
  → coupon:{couponId}:issue-result-counts Hash를 원자적으로 증가
  → GET /api/admin/coupons/{couponId}/issue-result-counts
  → issue-dashboard.html이 5초마다 조회
```

예약 보상은 `compensate-coupon.lua`에서 같은 Hash의 `COMPENSATED`를 증가시킨다.

## 화면 카운터와 Redis Hash 필드

| 필드 | 의미 | 요청·실패 합계 포함 |
|---|---|---|
| `RESERVED` | 예약 성공 | 전체 요청에 포함 |
| `SOLD_OUT` | 재고 소진 | 실패 및 전체 요청에 포함 |
| `DUPLICATE_ISSUE` | 같은 회원의 중복 요청 | 실패 및 전체 요청에 포함 |
| `NOT_OPEN_YET` | 발급 시작 전 요청 | 실패 및 전체 요청에 포함 |
| `ISSUE_CLOSED` | 발급 종료 후 요청 | 실패 및 전체 요청에 포함 |
| `STOCK_NOT_INITIALIZED` | Redis 재고 미초기화 | 실패 및 전체 요청에 포함 |
| `METADATA_NOT_INITIALIZED` | 발급 기간 메타데이터 미초기화 | 실패 및 전체 요청에 포함 |
| `COMPENSATED` | 예약 후속 처리 실패로 원복 | 별도 운영 지표이며 요청·실패 합계에서 제외 |

누락된 Hash 필드는 0으로 해석한다. 음수·숫자가 아닌 값, 합산 오버플로, Redis 접근 실패는 API의 `SERVICE_UNAVAILABLE` 응답으로 변환한다.

### 언제 증가하는가

대시보드는 Redis Hash의 값을 계산하거나 추정하지 않는다. `reserve-and-append-event.lua`와 `compensate-coupon.lua`가 원자적으로 증가시킨 값을 API가 그대로 읽어 온다. 따라서 프론트에 항목이 항상 있어도 해당 상황의 요청이 없으면 값은 `0`이다.

| 화면 항목 | 증가 조건 | 기본 k6 시나리오 |
|---|---|---|
| 발급 성공 (`RESERVED`) | 발급 기간 중, 재고가 있고, 해당 회원이 처음 발급을 요청 | 발생 |
| 재고 소진 (`SOLD_OUT`) | 발급 기간 중 Redis 재고가 0 이하인 요청 | 발생 |
| 중복 발급 (`DUPLICATE_ISSUE`) | 이미 `issued-members` Set에 있는 회원의 재요청 | 발생 |
| 발급 시작 전 (`NOT_OPEN_YET`) | Redis 서버 시간이 `openAtEpochSecond`보다 이른 요청 | 기본 스크립트에서는 미발생 |
| 발급 종료 (`ISSUE_CLOSED`) | Redis 서버 시간이 `closeAtEpochSecond`와 같거나 지난 요청 | 기본 스크립트에서는 미발생 |
| 재고 미초기화 (`STOCK_NOT_INITIALIZED`) | `coupon:{couponId}:stock` 키가 없는 상태의 요청 | 정상 초기화된 기본 스크립트에서는 미발생 |
| 메타데이터 미초기화 (`METADATA_NOT_INITIALIZED`) | 재고는 있으나 발급 시작·종료 시각 Hash 필드가 없는 요청 | 정상 초기화된 기본 스크립트에서는 미발생 |
| 보상 처리 (`COMPENSATED`) | DB 동기화가 재시도 한도를 넘어 포기되어 이미 예약된 Redis 상태를 원복 | 정상 동기화에서는 미발생 |

`STOCK_NOT_INITIALIZED`와 `METADATA_NOT_INITIALIZED`는 사용자 입력 오류가 아니라 Redis 발급 준비 누락·키 유실을 감지하는 운영 상태다. 두 경우 발급 API는 안전하게 `COUPON_ISSUE_NOT_READY`(503)로 거절한다.

`COMPENSATED`는 새 요청의 실패가 아니다. Redis 예약 성공 뒤 Stream 소비자가 DB 반영을 반복 시도하고, 최대 재시도 횟수를 넘으면 해당 회원을 `issued-members` Set에서 제거하고 재고를 1 복구한다. 이 원복이 실제로 적용된 횟수만 증가하므로 전체 요청과 실패 합계에서는 제외한다.

## API와 화면 동작

- API: `GET /api/admin/coupons/{couponId}/issue-result-counts`
- 로컬 데모 화면: `http://localhost:8080/issue-dashboard.html`
- 기본 쿠폰 ID: `301`
- 자동 갱신: 5초
- 기본 테마: 라이트
- 테마 선택: 브라우저 `localStorage`에 저장

존재하지 않는 쿠폰은 404 `COUPON_NOT_FOUND`로 응답한다. 화면은 이를 네트워크 장애와 구분해 쿠폰 ID가 포함된 안내를 보여주고, 이전 쿠폰의 카운터가 남아 오해를 만들지 않도록 표시값을 0으로 초기화한다. 그 밖의 일시적 연결 오류는 마지막 정상 값을 유지하면서 오류 안내를 표시한다.

### 요청과 응답

쿠폰 ID는 양의 정수 path variable이며 요청 본문과 query parameter는 없다.

```http
GET /api/admin/coupons/4/issue-result-counts
```

성공하면 현재 Redis 누적값을 공통 응답 봉투로 반환한다. `totalRequests`는 `reserved + failed`이며, `failed`는 `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `STOCK_NOT_INITIALIZED`, `METADATA_NOT_INITIALIZED`의 합이다. `compensated`는 별도 지표다.

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
    "compensated": 0
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

애플리케이션을 실행하고 대시보드를 먼저 연 뒤 다음 스크립트로 발급 요청을 만든다.

```powershell
k6 run load-test/issue-dashboard.js
```

환경에 따라 시연 쿠폰 ID가 다르면 화면의 쿠폰 ID와 k6의 `COUPON_ID`를 같은 값으로 지정한다.

```powershell
k6 run -e COUPON_ID=4 load-test/issue-dashboard.js
```

기본 시나리오는 테스트 시작 시 `/api/admin/load-test/reset`으로 대상 쿠폰 상태를 초기화한 뒤, 60초 동안 고유 회원 12,000건과 중복 회원 100건을 요청한다. 다른 요청이 없다면 결과는 `RESERVED` 10,000건, `SOLD_OUT` 2,000건, `DUPLICATE_ISSUE` 100건, 전체 요청 12,100건이다. 기간 밖 요청과 초기화 누락·보상은 이 기본 시나리오가 의도적으로 만들지 않는 운영 상태다.

### VU가 아닌 요청률 방식인 이유

고유 회원 발급 시나리오는 `constant-arrival-rate`로 초당 `REQUEST_RATE`(기본 200)건을 60초 동안 시작한다. 따라서 다른 요청이 없고 dropped iteration이 없다면 정확히 12,000번의 고유 회원 발급을 시도한다. 재고 10,000건을 기준으로 발급 성공 10,000건과 재고 소진 2,000건이라는 대시보드 기대값을 계산·비교할 수 있다.

VU 기반 executor는 "동시에 몇 명이 반복 요청하는가"를 정할 뿐, 응답 시간이 달라지면 같은 시간 동안 만들어지는 요청 수가 바뀐다. 이 대시보드는 Redis 집계가 정해진 요청량과 일치하는지 확인하는 목적이므로, 동시 사용자 수보다 요청 시작률을 고정하는 방식이 적합하다.

`preAllocatedVUs=100`, `maxVUs=1000`은 요청률을 처리하기 위한 k6 실행 자원이다. 부하 기준이 100 또는 1,000명의 사용자는 아니다. 서버가 느려져 필요한 VU가 `maxVUs`를 넘으면 k6가 iteration을 시작하지 못할 수 있으므로, 실행 결과의 `dropped_iterations`도 함께 확인해야 한다.

중복 발급 시나리오만은 같은 회원 ID로 정확히 100번 요청하는 것이 목적이므로 `shared-iterations`를 사용한다. 이때 `vus=5`는 100건을 병렬로 보낼 실행 수이고, 요청 총량은 `iterations=100`으로 고정된다.

## 한계

- Redis에는 현재 누적값만 있으므로 과거 시점의 변화나 초당 처리량을 복원할 수 없다.
- 화면은 단일 쿠폰을 조회하는 개발 도구이며 사용자 인증·권한 관리가 포함된 운영 콘솔이 아니다.
- Redis Hash의 보존 기간은 쿠폰 데이터 정리 정책과 함께 별도로 관리해야 한다.
- 장기간 추세, 다중 인스턴스 집계, 경보는 [Prometheus·Grafana 방식](../prometheus-grafana-issue-dashboard/README.md)의 범위다.
