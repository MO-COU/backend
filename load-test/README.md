# MO-COU 쿠폰 발급 k6 테스트

MO-COU 선착순 쿠폰 발급 시스템의 대용량 트래픽 동시성 처리 및 정합성을 검증하기 위한 k6 부하 테스트 도구 모음입니다.

---

## 📋 테스트 시나리오 구성

모든 정식 시나리오는 재고 10,000장을 사용한다. V1~V3는 팀 합의 시나리오이고 V4~V6는 부하 형태를 비교하기 위한 추가 시나리오다.

| 구분 | 시나리오 | 사용자·부하 | 램프업 | 요청 방식 | 총 요청 수 | 확인 목적 | 스크립트 |
| :---: | :--- | ---: | :---: | :--- | :---: | :--- | :--- |
| 필수 | `V1_RAMP_20000` | 20,000 VU | 60초·1초 단위 | 활성 VU가 같은 회원 ID로 반복 요청 | 서버 속도에 따라 변동 | 최대 처리량, 중복·품절 방어 | `rush-issue.js` |
| 필수 | `V2_SPIKE_20000` | 20,000 VU | 없음 | 사용자마다 1회 요청 | 20,000건 | 공식 동시 요청 조건 | `spike-issue.js` |
| 필수 | `V3_SPIKE_50000` | 50,000 VU | 없음 | 사용자마다 1회 요청 | 50,000건 | 더 큰 순간 부하와 5xx 확인 | `spike-issue.js` |
| 추가 | `V4_RAMP_ONCE_20000` | 20,000 VU | 60초·1초 단위 | 사용자마다 1회 요청 | 20,000건 | 실제 사용자형 점진 유입 | `ramp-once-issue.js` |
| 추가 | `V5_RATE_4000_RPS` | 4,000 req/s | 없음 | 5초 동안 요청률 고정 | 20,000건 | 요청률 유지와 dropped iteration 확인 | `rate-issue.js` |
| 추가 | `V6_REPEAT_1_TO_3` | 20,000 VU | 없음 | 사용자마다 1~3회 요청 | 39,999건 | 제한적 재시도와 중복 방어 | `repeat-issue.js` |

보조 스크립트는 정식 시나리오와 별도로 사용한다.

| 스크립트 | 설정 | 목적 |
| :--- | :--- | :--- |
| `smoke-issue.js` | 10 VU·각 1회 | 발급 API의 기본 동작 확인 |
| `duplicate-issue.js` | 동일 회원 1명이 10회 요청 | 최초 1회만 성공하고 나머지가 중복으로 차단되는지 확인 |

---

관리자 실행 API에서 쿠폰 회차와 시나리오를 각각 선택한다. 같은 회차에서도 초기화 후 다른 시나리오를
실행할 수 있고, 실행 조건과 결과는 `coupon_issue_run`에 함께 기록된다.

| API 시나리오 | 시연 예시 회차 | 실행 설정 |
| :--- | :---: | :--- |
| `V1_RAMP_20000` | 301 | 20,000명, 60초 ramp-up |
| `V2_SPIKE_20000` | 302 | 20,000명, 순간 유입 |
| `V3_SPIKE_50000` | 303 | 50,000명, 순간 유입 |
| `V4_RAMP_ONCE_20000` | 304 | 20,000명, 60초 ramp-up, 회원별 1회 |
| `V5_RATE_4000_RPS` | 305 | 4,000 req/s, 5초, 총 20,000건 |
| `V6_REPEAT_1_TO_3` | 306 | 20,000명, 사용자별 1~3회, 총 39,999건 |

```http
POST /api/admin/load-tests
Content-Type: application/json

{"couponId":301,"scenario":"V1_RAMP_20000"}
```

위 표는 시연할 때 사용할 기본 조합일 뿐 API가 강제하는 매핑이 아니다. 예를 들어 301회차에서
`V2_SPIKE_20000`을 선택할 수도 있다. 선택한 회차는 `OPEN`이고 DB와 Redis 모두 발급 전 초기 상태여야 한다.

모든 발급 스크립트는 실제 발급 상태를 변경한다. 테스트마다 전용 쿠폰을 사용하고 DB와 Redis를 초기 상태로 복구한다.

## 사전 준비

다음 기능이 통합된 상태에서 실행한다.

- `POST /api/coupons/{couponId}/issues` 발급 API
- MySQL의 시연용 쿠폰·회원 데이터
- DB 기준 Redis 재고·발급 시간 초기화
- Redis Stream 이벤트의 DB 적재 로직

발급 API가 아직 `dev`에 통합되지 않았다면 스크립트 구문 검증까지만 진행하고, 실제 호출은 통합 후에 진행한다.

### macOS (Homebrew)
```bash
brew install k6
```

### Docker 실행
```bash
docker run --rm -i \
  -e TARGET=http://host.docker.internal:8080 \
  -e COUPON_ID=301 \
  grafana/k6 run - <load-test/smoke-issue.js
```

Docker 안에서 `localhost`는 Mac이 아니라 k6 컨테이너 자신을 가리키므로 `host.docker.internal`을 사용한다.

---

## 테스트 실행 방법

### 테스트마다 먼저 초기화

Consumer가 Redis Stream 처리를 마친 뒤 테스트 쿠폰 데이터만 초기화한다. 다른 쿠폰이나 Redis 전체 데이터는 지우지 않는다.

1. 테스트 쿠폰의 검증 결과와 발급 이력을 DB에서 삭제한다.
2. `coupon_stock.remaining_quantity`를 테스트 재고로 되돌린다.
3. 해당 쿠폰의 Redis 재고·회원·메타데이터·Stream Key만 삭제한다.
4. DB 기준 Redis 초기화를 다시 실행한다.
5. Redis 재고와 DB 재고가 같은지 확인한다.

현재 초기화 서비스는 기존 Redis Key가 있으면 값을 덮어쓰지 않는다. 따라서 DB 재고만 되돌리면 초기화되지 않으며, 해당 쿠폰 Key 삭제가 먼저 필요하다.

### 1. 스모크 테스트 (로컬 검증용)
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=10 load-test/smoke-issue.js
```

### 2. V1 램프업 테스트 (20,000명·60초)
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e EXPECTED_STOCK=10000 load-test/rush-issue.js
```

단계별로 확인할 때는 `VUS`와 `RAMP_UP`을 지정한다.

```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=1000  -e EXPECTED_STOCK=1000  -e RAMP_UP=10s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=5000  -e EXPECTED_STOCK=5000  -e RAMP_UP=30s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=10000 -e EXPECTED_STOCK=10000 -e RAMP_UP=60s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=20000 -e EXPECTED_STOCK=10000 -e RAMP_UP=60s load-test/rush-issue.js
```

`EXPECTED_STOCK`은 실행 직전 Redis 재고와 같게 넣는다. 예를 들어 재고 1,000장에 2,000명이 요청하면 다음과 같다.

`rush-issue.js`는 `ramping-vus`로 0명에서 20,000명까지 60초 동안 사용자를 늘린다. 1초마다 목표 VU를 다시 계산하므로 초당 약 333명씩 증가하고 마지막 60초에 20,000명에 도달한다. 각 VU에는 서로 다른 `memberId`를 부여하고 테스트가 끝날 때까지 같은 회원 ID로 반복 요청한다. 총 요청 수는 서버 응답 속도에 따라 달라지며 고정하지 않는다. 이 시나리오는 중복·품절 방어와 시스템 최대 처리량을 확인한다.

```bash
k6 run \
  -e TARGET=http://localhost:8080 \
  -e COUPON_ID=301 \
  -e VUS=20000 \
  -e RAMP_UP=60s \
  -e HOLD=10s \
  -e EXPECTED_STOCK=10000 \
  load-test/rush-issue.js
```

각 단계는 같은 쿠폰에 연속 실행하지 않고 위 초기화를 다시 한 뒤 실행한다.

### 3. 순간 유입 스파이크 테스트

20,000명과 50,000명 버전은 같은 스크립트를 사용하고 `VUS`만 다르게 지정한다.

```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=302 \
  -e VUS=20000 -e EXPECTED_STOCK=10000 load-test/spike-issue.js

k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=303 \
  -e VUS=50000 -e EXPECTED_STOCK=10000 load-test/spike-issue.js
```

`per-vu-iterations`로 VU마다 정확히 1회 요청한다. 이는 클릭 시각이 완전히 같은 것을 보장한다는 뜻이 아니라, k6가 VU를 준비한 뒤 가능한 한 짧은 구간에 요청을 몰아 보내는 테스트다. 50,000 VU는 부하 생성기 자체의 CPU·메모리 한계도 함께 확인해야 한다.

### 4. 중복 발급 방어 테스트
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e MEMBER_ID=999999 load-test/duplicate-issue.js
```

---

## 결과 검증 기준

### 1. k6 터미널 지표 검증
* `http_reqs`: 서버 응답 속도에 따라 달라지는 전체 반복 요청 수 확인
* `issue_success_202`: 정확히 **10,000**건
* `issue_sold_out_409`: V1은 **1건 이상**, V2는 **10,000건**, V3는 **40,000건**
* `issue_duplicate_409`: V1은 **1건 이상**, V2/V3는 **0건**
* `issue_system_error_5xx`: **0**건
* `issue_other_error`: **0**건
* `http_req_duration (p95)`: **2초(2000ms) 이내**

### 2. 비동기 DB 적재 완료 후 데이터베이스 검증 (MySQL)
```sql
-- 1. 최종 발급된 쿠폰 수량 확인 (정확히 10,000건이어야 함)
SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = 301;

-- 2. 중복 발급된 회원이 있는지 확인 (0건이어야 함)
SELECT member_id, COUNT(*)
FROM coupon_issue
WHERE coupon_id = 301
GROUP BY member_id
HAVING COUNT(*) > 1;

-- 3. Redis 잔여 재고 확인 (0이어야 함)
-- redis-cli GET 'coupon:{301}:stock' -> 0
```

`202 Accepted`는 Redis 예약 성공을 뜻한다. DB 적재 완료는 Redis Stream 처리가 끝난 뒤 위 SQL과 B팀 정합성 검증 결과로 별도 확인한다.

전체 검증 절차와 결과 기록 형식은 [`docs/test/full-issue-flow-verification.md`](../docs/test/full-issue-flow-verification.md)를 참고한다.
DB 사전 점검은 `load-test/verify-issue-result.sql`을 실행하면 발급 건수, 잔여 재고, 중복 발급과 발급 이력 불일치를 한 번에 확인할 수 있다. 공식 최종 판정은 [`docs/b1/consistency-rules.md`](../docs/b1/consistency-rules.md)의 R1~R7 검증 결과를 사용한다.

전체 흐름은 아래 명령으로 실행할 수 있다. 아직 Consumer가 연결되지 않은 경우 검증 옵션을 `false`로 둔다.

```bash
MODE=smoke VERIFY_DB=false VERIFY_REDIS=false ./load-test/run-full-flow.sh
```

데이터와 Consumer 연결 후에는 `VERIFY_DB=true VERIFY_REDIS=true`를 지정한다.
실행 결과는 기본적으로 `load-test/results/<실행시각>_<모드>_<쿠폰ID>-summary.json`에 저장된다.

같은 쿠폰에 기존 발급 데이터가 있어도 신규 반영 건수를 기준으로 기다리려면 `EXPECTED_NEW_DB_COUNT`를 지정한다.

```bash
MODE=v1-ramp-20000 COUPON_ID=301 VUS=20000 EXPECTED_STOCK=10000 \
  EXPECTED_NEW_DB_COUNT=10000 VERIFY_DB=true VERIFY_REDIS=true \
  VERIFY_CONSISTENCY=true ISSUE_RUN_ID=15 ./load-test/run-full-flow.sh
```

결과 파일명과 k6 태그에 쓰는 `TEST_LABEL`은 DB의 숫자형 `run_id`와 별개다.

`VERIFY_CONSISTENCY=true`이면 `ISSUE_RUN_ID`를 대상으로
`POST /api/admin/verifications?issueRunId={ISSUE_RUN_ID}`를 호출하고 결과가 `PASS`, 위반 0건인지 확인한다.
JSON 응답을 읽기 위해 `jq`가 필요하다.
