# MO-COU 쿠폰 발급 k6 테스트

MO-COU 선착순 쿠폰 발급 시스템의 대용량 트래픽 동시성 처리 및 정합성을 검증하기 위한 k6 부하 테스트 도구 모음입니다.

---

## 📋 테스트 시나리오 구성

| 스크립트 | 대상 VU | 목적 및 시나리오 |
| :--- | :---: | :--- |
| **`smoke-issue.js`** | 10 VUs (각 1회) | 발급 API가 정상 동작하는지 소규모로 확인하는 스모크 테스트 |
| **`rush-issue.js`** | **20,000 VUs** (60초) | **[메인 시나리오]** 재고 10,000장에 20,000명이 몰렸을 때 10,000장 완판 및 0건 초과/중복 발급 검증 |
| **`duplicate-issue.js`** | 1 VU (10회 반복) | 동일한 회원이 10번 연타했을 때 최초 1회만 성공하고 9회는 `409 DUPLICATE`로 차단되는지 검증 |

---

세 스크립트 모두 실제 발급 상태를 변경한다. 스모크나 중복 테스트를 실행한 뒤 본 테스트를 이어서 실행하지 않고, 테스트마다 새로운 쿠폰을 사용하거나 DB와 Redis를 초기 상태로 복구한다.

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

### 1. 스모크 테스트 (로컬 검증용)
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 load-test/smoke-issue.js
```

### 2. 본 선착순 부하 테스트 (2만 명 동시 접속)
```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 load-test/rush-issue.js
```

단계별로 확인할 때는 `VUS`와 `RAMP_UP`을 지정한다.

```bash
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=1000  -e RAMP_UP=10s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=5000  -e RAMP_UP=30s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=10000 -e RAMP_UP=60s load-test/rush-issue.js
k6 run -e TARGET=http://localhost:8080 -e COUPON_ID=301 -e VUS=20000 -e RAMP_UP=60s load-test/rush-issue.js
```

각 단계는 같은 쿠폰에 연속 실행하지 않는다. 새 쿠폰을 사용하거나 Redis 재고·발급 회원·Stream과 DB 발급 이력을 테스트 초기 상태로 복구한 뒤 실행한다.

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
