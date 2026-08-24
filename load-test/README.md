# MO-COU 쿠폰 발급 k6 테스트

MO-COU 선착순 쿠폰 발급 시스템의 대용량 트래픽 동시성 처리 및 정합성을 검증하기 위한 k6 부하 테스트 도구 모음입니다.

---

## 📋 테스트 시나리오 구성

| 스크립트 | 대상 VU | 목적 및 시나리오 |
| :--- | :---: | :--- |
| **`smoke-issue.js`** | 10 VUs (각 1회) | 발급 API가 정상 동작하는지 소규모로 확인하는 스모크 테스트 |
| **`rush-issue.js`** | **20,000명** (60초) | **[메인 시나리오]** 재고 10,000장에 20,000명이 몰렸을 때 10,000장 완판 및 0건 초과/중복 발급 검증 |
| **`duplicate-issue.js`** | 1 VU (10회 반복) | 동일한 회원이 10번 연타했을 때 최초 1회만 성공하고 9회는 `409 DUPLICATE`로 차단되는지 검증 |

---

세 스크립트 모두 실제 발급 상태를 변경한다. 테스트마다 전용 쿠폰을 사용하고 DB와 Redis를 초기 상태로 복구한다.

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

### 2. 본 선착순 부하 테스트 (2만 명 동시 접속)
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

`rush-issue.js`는 `VUS` 값을 총 요청 수로 사용한다. 기본 500개의 작업 VU가 요청 사이의 간격을 나눠 가지며 `RAMP_UP` 동안 정확히 해당 요청 수를 보낸다. 로컬 자원에 맞춰 `WORKER_VUS`를 조정할 수 있다.

```bash
k6 run \
  -e TARGET=http://localhost:8080 \
  -e COUPON_ID=301 \
  -e VUS=2000 \
  -e RAMP_UP=10s \
  -e EXPECTED_STOCK=1000 \
  load-test/rush-issue.js
```

각 단계는 같은 쿠폰에 연속 실행하지 않고 위 초기화를 다시 한 뒤 실행한다.

### 3. 중복 발급 방어 테스트
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e MEMBER_ID=999999 load-test/duplicate-issue.js
```

---

## 결과 검증 기준

### 1. k6 터미널 지표 검증
* `http_reqs`: 정확히 **20,000**건
* `issue_success_202`: 정확히 **10,000**건
* `issue_sold_out_409`: 정확히 **10,000**건
* `issue_duplicate_409`: **0**건
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
MODE=rush COUPON_ID=301 VUS=20000 EXPECTED_STOCK=10000 \
  EXPECTED_NEW_DB_COUNT=10000 VERIFY_DB=true VERIFY_REDIS=true \
  VERIFY_CONSISTENCY=true ./load-test/run-full-flow.sh
```

결과 파일명과 k6 태그에 쓰는 `TEST_LABEL`은 DB의 숫자형 `run_id`와 별개다.

`VERIFY_CONSISTENCY=true`이면 `POST /api/admin/verifications`로 검증을 시작하고 결과가 `PASS`, 위반 0건인지 확인한다. JSON 응답을 읽기 위해 `jq`가 필요하다.
