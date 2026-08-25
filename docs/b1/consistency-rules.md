# B1 정합성 검증 규칙 명세

`com.mocou.consistency`가 검사하는 규칙과, 각 규칙이 **무엇을 위반으로 보는지**를 확정한다.

판정식이 코드에만 있으면 "이건 위반이 아니라 정상인데?" 논쟁이 났을 때 근거가 없다. 데이터 규격은 [B1 더미데이터 규격과 적재 가이드](./datagen.md)에 있으며, 이 문서는 그 규격을 "정상"의 정의로 삼는다.

관련 요구사항: `FR-3.1`~`FR-3.5`, `NFR-2`, `NFR-3`

---

## Quick Start

```bash
# 1. 검증 실행 — 202와 runId가 즉시 돌아온다
curl -X POST 'http://localhost:8080/api/admin/verifications'

# 2. 결과 조회 — status가 COMPLETED가 되면 verdict를 본다
curl 'http://localhost:8080/api/admin/verifications/{runId}'
```

| 항목 | 값 |
| --- | --- |
| 소요 | 300만 건 전량 약 **90초** (실행은 백그라운드, 응답은 즉시) |
| 대상 | 발급 이력 **300만 건 전체**. 표본·최근 N건 검사는 하지 않는다 |
| 규칙 수 | **8종** (`R1`~`R8`) |
| 판정 | `PASS` / `FAIL` / **`ERROR`**(판정 불가) |
| 중복 실행 | `409 VERIFICATION_ALREADY_RUNNING` |

**부하 테스트 직후라면 아래를 먼저 확인한다.** 둘 다 `0`이 아니면 `R8`이 판정 불가가 되어 전체가 `ERROR`로 끝난다.

```bash
docker exec mocou-redis redis-cli XLEN "coupon:{301}:issue-stream"
docker exec mocou-redis redis-cli XPENDING "coupon:{301}:issue-stream" coupon-issue-db-sync
```

---

## 규칙 한눈에 보기

| ID | `rule_name` | 무엇을 잡나 | `checked_count` 단위 | 실측(300만 건) |
| --- | --- | --- | --- | ---: |
| `R1` | `DUPLICATE_ISSUE` | 한 회원이 같은 쿠폰 2장 이상 | 발급 건 | 3,000,000 |
| `R2` | `OVER_ISSUE` | 발급 건수 > 총 재고 | 쿠폰(재고 행) | 301 |
| `R3` | `STOCK_MISMATCH` | 총재고 ≠ 발급 + 잔여 | 쿠폰(재고 행) | 301 |
| `R4` | `STATE_TIMESTAMP_MISMATCH` | 한 행 안의 상태·시각 모순 (8항목) | 발급 건 | 3,000,000 |
| `R5` | `HISTORY_MISMATCH` | 이력 체인 불일치 (4항목) | **항목별 합** | 17,972,000 |
| `R6` | `ORPHAN_REFERENCE` | 존재하지 않는 대상 참조 | 참조 관계 4종의 합 | 11,986,301 |
| `R7` | `TOOL_RELIABILITY` | 주입한 위반을 실제로 검출하는가 | 시나리오 수 | — |
| `R8` | `REDIS_DB_MISMATCH` | Redis 발급 결과 ≠ DB 이력 | 회원 대조 + 재고 대조 | 1 |

**`checked_count`는 규칙마다 세는 단위가 다르다.** `R5`가 발급 건수보다 큰 것, `R6`이 300만을 넘는 것은 정상이다. 항목·참조가 여럿이라 각각을 더하기 때문이다.

---

## 1. 원칙

| 원칙 | 이유 |
| --- | --- |
| **검출하고 기록할 뿐, 고치지 않는다** | 고치면 재실행 결과가 달라지고(`NFR-3` 위반) 원인 조사 증거가 사라진다 |
| **절대 건수가 아니라 불변식으로 판정한다** | `ISSUED가 14,000건`이 아니라 `ISSUED인 건은 expires_at > T`. 앞의 형태는 만료 배치가 도는 순간 전건 실패한다 |
| **300만 건 전체가 대상이다** (`FR-3.4`) | 표본 검사나 최근 N건 검사는 하지 않는다 |

---

## 2. 실행

검증 1회가 `verification_run` 1행이다. 규칙별 집계는 `verification_rule_result`, 위반 상세는 `verification_violation`에 남는다.

### 2.1 실행과 조회

```bash
# 실행. 부하 테스트 직후 검증이면 ?issueRunId=<번호>를 붙인다
curl -X POST 'http://localhost:8080/api/admin/verifications'

# 조회
curl 'http://localhost:8080/api/admin/verifications/{runId}'
```

| 응답 필드 | 값 |
| --- | --- |
| `status` | `RUNNING` 아직 도는 중 / `COMPLETED` 끝남 |
| `verdict` | `PASS` / `FAIL` / `ERROR`. **진행 중이면 `null`** |
| `snapshotAt` | 검증 기준 시점. 모든 규칙이 이 시점의 데이터를 본다 |
| `rules[].status` | `CHECKED` 그 규칙이 끝까지 실행됨 / `FAILED` 실행하지 못함 |
| `rules[].failureReason` | `FAILED`일 때만 사유 |

> **`status`가 두 곳에 있고 뜻이 다르다.** 바깥은 **실행이 끝났는지**, `rules[]` 안쪽은 **그 규칙이 판정을 냈는지**를 뜻한다.

DB를 직접 볼 때는 `verification_run`·`verification_rule_result`를 `run_id`로 조인한다. `finished_at`이 `NULL`이면 아직 도는 중이다.

### 2.2 중복 실행 차단

**진행 중에 다시 요청하면 `409 VERIFICATION_ALREADY_RUNNING`.** 겹쳐 돌리면 300만 건 스캔이 두 배가 되고 결과 행이 둘로 갈린다.

단, 시작한 지 **5분**(`mocou.consistency.stale-run-minutes`)이 지나도 끝나지 않은 실행은 죽은 것으로 보고 새 실행을 허용한다. 실행 중 애플리케이션이 죽으면 `finished_at`이 영원히 `NULL`로 남는데, 그것까지 진행 중으로 보면 이후 검증이 아예 막힌다.

### 2.3 기준 시각 확보

**모든 규칙 쿼리를 하나의 읽기 전용 `REPEATABLE READ` 트랜잭션에서 실행한다.** 규칙별로 트랜잭션을 나누면 그 사이 만료 배치가 상태를 바꿔, 규칙마다 다른 시점의 데이터를 보게 된다.

`verification_run.snapshot_at`에는 트랜잭션 시작 직후 읽은 DB 시각을 기록한다. 판정식의 `T`가 이 값이다. 판정 대상 시각이 전부 DB가 찍은 값이므로 애플리케이션 시각은 쓰지 않는다.

<details>
<summary><b>트레이드오프 — 긴 읽기 트랜잭션과 undo log purge</b></summary>

긴 읽기 트랜잭션은 undo log purge를 지연시킨다. 검증이 도는 90초 동안 InnoDB는 그 시점의 읽기 뷰를 유지해야 하므로, 그동안 변경된 행의 이전 버전을 정리하지 못한다.

문제가 되면 `snapshot_at` 컬럼 필터 방식으로 전환한다. 트랜잭션을 짧게 끊는 대신 각 쿼리에 `WHERE created_at <= :T` 형태의 필터를 걸어 시점을 맞추는 방식이다.

</details>

### 2.4 검증 실행 기록

검증 대상이 무엇이냐에 따라 `verification_run.issue_run_id`를 다르게 채운다.

| `issue_run_id` | 언제 | 의미 |
| --- | --- | --- |
| `NULL` | 더미데이터 300만 건 전체 검증 | 특정 발급 실행이 아니라 DB 전체 상태를 본 검증 |
| 값 있음 | 부하 테스트 직후 검증 | 그 발급 실행이 정합했는지 본 검증 |

`NULL`이 아닌 값을 넣으면 `coupon_issue_run`에 실재하는 행이어야 한다. 없는 `run_id`는 `ERROR 1452`로 거부된다.

같은 쿠폰에 대한 발급 실행과 검증 실행은 모두 여러 번 기록할 수 있다.

---

## 3. 규칙

`rule_name`은 `verification_rule_result.rule_name`에 그대로 들어가는 상수다.

`checked_count`는 그 규칙이 실제로 대조한 항목의 수, `violation_count`는 그중 어긋난 수다.

> **규칙이 실행에 실패하면 둘 다 0이 되는데, 검사 대상이 없어 0인 정상 실행과 값이 같다.** 그래서 `verification_rule_result.status`에 `CHECKED`/`FAILED`를 따로 남기고, `FAILED`면 `failure_reason`에 사유를 적는다.

### R1. `DUPLICATE_ISSUE` — 중복 발급

한 회원이 같은 쿠폰을 2장 이상 받았는가 (`FR-2.3`).

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_issue` 전체 행 수 |
| `target_type` | `COUPON_MEMBER_PAIR` |
| `target_id` / `target_id2` | `coupon_id` / `member_id` |

**현재 구조에서는 위반이 나올 수 없다.** 유니크 인덱스가 INSERT 단계에서 막는다. 제약이 살아 있는지 확인하는 용도이며, Redis→DB 비동기 동기화 경로(#39)가 붙으면 검출 대상이 생긴다.

<details>
<summary><b>판정 쿼리</b></summary>

```sql
SELECT coupon_id, member_id, COUNT(*) AS issue_count
FROM coupon_issue
GROUP BY coupon_id, member_id
HAVING COUNT(*) > 1
```

`GROUP BY (coupon_id, member_id)`는 `uk_issue_coupon_member`의 컬럼 순서와 같아 인덱스만으로 집계된다.

</details>

### R2. `OVER_ISSUE` — 초과 발급

쿠폰별 발급 건수가 총 재고를 넘었는가 (`FR-2.1`, `NFR-1`).

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_stock` 행 수 |
| `target_type` | `COUPON` |
| `target_id` | `coupon_id` |
| `detail` | `총재고 10000, 발급 10001` |

> **`LEFT JOIN`이어야 한다.** `INNER JOIN`으로 바꾸면 발급 이력이 없는 시연 회차가 검사 범위에서 조용히 빠진다.

초과 발급을 막는 DB 제약은 없다. 재고 차감이 애플리케이션·Redis 책임이라 실제로 위반이 검출될 수 있다.

<details>
<summary><b>판정 쿼리</b></summary>

```sql
SELECT s.coupon_id, s.total_quantity, COUNT(i.coupon_issue_id) AS issued
FROM coupon_stock s
LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
GROUP BY s.coupon_id, s.total_quantity
HAVING issued > s.total_quantity
```

</details>

### R3. `STOCK_MISMATCH` — 이력·재고 정합성

`총재고 = 발급 건수 + 잔여 재고`가 성립하는가 (`FR-3.3`, `NFR-2`).

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_stock` 행 수 |
| `target_type` | `COUPON` |
| `detail` | `총재고 10000, 발급 9998, 잔여 1` |

> 여기서도 **`LEFT JOIN`이 필수**다. `INNER JOIN`으로 바꾸면 시연 회차가 검사되지 않는데, 미검사와 통과가 결과에서 구분되지 않는다.

**적재 직후에는 위반이 나올 수 없다.** `remaining_quantity`를 발급 건수로 역산해 채우므로 정의상 성립한다. 발급 경로가 `remaining_quantity`를 갱신하지 않으면 부하 테스트 직후 검출된다.

<details>
<summary><b>판정 쿼리</b></summary>

```sql
SELECT s.coupon_id, s.total_quantity, s.remaining_quantity, COUNT(i.coupon_issue_id) AS issued
FROM coupon_stock s
LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
GROUP BY s.coupon_id, s.total_quantity, s.remaining_quantity
HAVING s.total_quantity <> s.remaining_quantity + issued
```

</details>

### R4. `STATE_TIMESTAMP_MISMATCH` — 상태·시각 모순

`coupon_issue` 한 행 안에서 상태와 시각이 서로 어긋나는가 (`FR-3.6`). **8개 항목**을 검사하며 `T`는 `snapshot_at`이다.

| 코드 | 조건 | 무엇을 잡나 |
| --- | --- | --- |
| `USED_WITHOUT_TIMESTAMP` | `status='USED' AND used_at IS NULL` | 사용 처리하며 시각을 안 남긴 경우 |
| `UNUSED_WITH_TIMESTAMP` | `status<>'USED' AND used_at IS NOT NULL` | 사용 취소 시 시각을 안 지운 경우 |
| `USED_BEFORE_ISSUED` | `used_at < issued_at` | 받기 전에 쓴 쿠폰 |
| `INVALID_VALIDITY` | `expires_at <= issued_at` | 유효기간이 0 이하 |
| `FUTURE_ISSUE` | `issued_at > T` | 미래에 발급된 쿠폰 |
| `EXPIRY_OVERDUE` | `status='ISSUED' AND expires_at <= T - G` | 만료 배치 정지·실패로 밀린 건 |
| `PREMATURE_EXPIRY` | `status='EXPIRED' AND expires_at > T` | 기간이 남았는데 만료된 건 |
| `ISSUED_BEFORE_SIGNUP` | `m.created_at > i.issued_at` | 가입 전에 받은 쿠폰 |

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_issue` 전체 행 수 |
| `violation_count` | **위반 행 수.** 한 행이 여러 항목을 어겨도 1로 센다 |
| `target_type` | `COUPON_ISSUE` |
| `detail` | 그 행이 어긴 항목 코드 목록 |

**유예 `G`**

```
G = mocou.lifecycle.expiration.fixed-delay-ms × 5
```

만료 전환은 배치가 일괄 처리하므로(`FR-2.6`) 만료 시각과 상태 전환 사이에 지연이 있다. 정상 지연은 최대 2주기다. **상수로 두면 배치 주기를 조정했을 때 함께 어긋나므로 배수만 고정한다.**

<details>
<summary><b>판정 쿼리와 주의점</b></summary>

```sql
SELECT i.coupon_issue_id,
       CONCAT_WS(',',
         CASE WHEN i.status = 'USED' AND i.used_at IS NULL THEN 'USED_WITHOUT_TIMESTAMP' END,
         CASE WHEN i.status <> 'USED' AND i.used_at IS NOT NULL THEN 'UNUSED_WITH_TIMESTAMP' END,
         CASE WHEN i.used_at < i.issued_at THEN 'USED_BEFORE_ISSUED' END,
         CASE WHEN i.expires_at <= i.issued_at THEN 'INVALID_VALIDITY' END,
         CASE WHEN i.issued_at > :T THEN 'FUTURE_ISSUE' END,
         CASE WHEN i.status = 'ISSUED' AND i.expires_at <= :T - INTERVAL :graceSeconds SECOND THEN 'EXPIRY_OVERDUE' END,
         CASE WHEN i.status = 'EXPIRED' AND i.expires_at > :T THEN 'PREMATURE_EXPIRY' END,
         CASE WHEN m.created_at > i.issued_at THEN 'ISSUED_BEFORE_SIGNUP' END
       ) AS reasons
FROM coupon_issue i
JOIN member m ON m.member_id = i.member_id
WHERE (i.status = 'USED' AND i.used_at IS NULL)
   OR (i.status <> 'USED' AND i.used_at IS NOT NULL)
   OR i.used_at < i.issued_at
   OR i.expires_at <= i.issued_at
   OR i.issued_at > :T
   OR (i.status = 'ISSUED' AND i.expires_at <= :T - INTERVAL :graceSeconds SECOND)
   OR (i.status = 'EXPIRED' AND i.expires_at > :T)
   OR m.created_at > i.issued_at
```

- `CONCAT_WS`는 `NULL` 인자를 건너뛰므로, 조건에 걸리지 않은 항목은 자동으로 빠진다.
- **필터는 `WHERE`에 쓴다.** `SELECT` 별칭에 `HAVING`을 걸면 `GROUP BY`가 없어 전체가 한 그룹으로 묶이고 **최대 1행만 반환된다.**
- `ISSUED_BEFORE_SIGNUP`은 300만 행 각각에 `member` PK 조회가 발생한다. 비용이 크면 이 항목만 별도 쿼리로 분리한다.

</details>

### R5. `HISTORY_MISMATCH` — 이력 체인 정합성

상태 이력이 현재 상태와 일치하고, 체인이 끊기지 않았는가 (`FR-3.1`).

`R4`가 한 행 안의 모순을 보는 반면 이 규칙은 `coupon_issue`와 `coupon_issue_history`의 **관계**를 본다. 약 600만 행을 조인·정렬하므로 비용도 성격도 달라 규칙을 분리했다.

| 코드 | 조건 |
| --- | --- |
| `MISSING_INITIAL_HISTORY` | `UNISSUED → ISSUED` 이력이 정확히 1건이 아님 |
| `FINAL_STATUS_MISMATCH` | 마지막 이력의 `to_status ≠ coupon_issue.status` |
| `BROKEN_CHAIN` | 직전 이력의 `to_status ≠ 다음 이력의 from_status` |
| `HISTORY_BEFORE_ISSUE` | 이력의 `changed_at < coupon_issue.issued_at` |

| 항목 | 값 |
| --- | --- |
| `checked_count` | **항목별 대상 수의 합.** 발급 건을 보는 항목은 `coupon_issue` 행 수, 이력을 보는 항목은 `coupon_issue_history` 행 수를 각각 더한다 |
| `violation_count` | **항목별 위반 수의 합.** 한 발급 건이 두 항목을 어기면 2로 센다 |
| `target_type` | `COUPON_ISSUE` |
| `target_id` | `coupon_issue_id` |

> **`checked_count`가 `coupon_issue` 행 수보다 큰 것이 정상이다.** 성격이 다른 항목 여럿을 한 이름으로 묶은 규칙이라, 항목마다 대상 집합이 다르다(발급 건 vs 이력 행). 하나로 합치면 어느 항목이 몇 개를 봤는지 사라진다.

위반도 같은 이유로 항목별로 센다. 한 발급 건이 "최초 이력 없음"과 "체인 끊김"을 동시에 어기면 2건이다. 행 단위로 세면 어느 항목이 몇 번 깨졌는지 알 수 없다.

<details>
<summary><b>판정 쿼리 — 윈도우 함수와 정렬 결정성</b></summary>

```sql
-- MISSING_INITIAL_HISTORY
SELECT i.coupon_issue_id, COUNT(h.history_id) AS initial_count
FROM coupon_issue i
LEFT JOIN coupon_issue_history h
       ON h.coupon_issue_id = i.coupon_issue_id
      AND h.from_status = 'UNISSUED'
      AND h.to_status = 'ISSUED'
GROUP BY i.coupon_issue_id
HAVING initial_count <> 1

-- FINAL_STATUS_MISMATCH
WITH last_history AS (
    SELECT coupon_issue_id, to_status,
           ROW_NUMBER() OVER (
               PARTITION BY coupon_issue_id
               ORDER BY changed_at DESC, history_id DESC
           ) AS rn
    FROM coupon_issue_history
)
SELECT i.coupon_issue_id, i.status, l.to_status
FROM coupon_issue i
JOIN last_history l ON l.coupon_issue_id = i.coupon_issue_id AND l.rn = 1
WHERE i.status <> l.to_status

-- BROKEN_CHAIN
WITH chain AS (
    SELECT coupon_issue_id, history_id, from_status,
           LAG(to_status) OVER (
               PARTITION BY coupon_issue_id
               ORDER BY changed_at, history_id
           ) AS prev_to_status
    FROM coupon_issue_history
)
SELECT coupon_issue_id, history_id, prev_to_status, from_status
FROM chain
WHERE prev_to_status IS NOT NULL AND prev_to_status <> from_status
```

- **정렬에 `history_id`를 함께 넣는다.** `changed_at`이 같은 이력이 있을 때 순서가 흔들리면 판정이 실행마다 달라진다.
- `uk_history_issue_idempotency`의 선두 컬럼이 `coupon_issue_id`라 파티션 단위 접근에는 도움이 되지만, `changed_at` 정렬에서 filesort가 발생할 수 있다. `EXPLAIN`으로 확인하고 결과를 실행계획 분석 항목에 기록한다.

</details>

### R6. `ORPHAN_REFERENCE` — 참조 무결성

존재하지 않는 회원·쿠폰·발급 건을 가리키는 행이 있는가.

| 항목 | 값 |
| --- | --- |
| `checked_count` | 검사한 참조 관계의 행 수 **합** (4종) |
| `target_type` | `COUPON_ISSUE` / `COUPON_ISSUE_HISTORY` / `COUPON_STOCK` |
| `detail` | 어느 참조가 끊겼는지 (`coupon_issue.member_id` 등) |

**FK가 걸려 있어도 검사한다.** `SET FOREIGN_KEY_CHECKS = 0`으로 적재하는 경로(초기화 스크립트, `mysqldump` 복원)가 있어 고아 행이 만들어질 수 있다.

> **`issue_failure_log`는 검사 대상에서 제외한다.** `COUPON_NOT_FOUND`를 기록하려면 존재하지 않는 ID도 넣을 수 있어야 해 FK를 걸지 않은 테이블이다. 포함하면 정상 데이터가 전부 위반으로 잡힌다.

<details>
<summary><b>판정 쿼리 — 참조 4종</b></summary>

```sql
SELECT 'coupon_issue.coupon_id' AS ref, i.coupon_issue_id AS target_id
FROM coupon_issue i LEFT JOIN coupon c ON c.coupon_id = i.coupon_id
WHERE c.coupon_id IS NULL
UNION ALL
SELECT 'coupon_issue.member_id', i.coupon_issue_id
FROM coupon_issue i LEFT JOIN member m ON m.member_id = i.member_id
WHERE m.member_id IS NULL
UNION ALL
SELECT 'coupon_issue_history.coupon_issue_id', h.history_id
FROM coupon_issue_history h LEFT JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
WHERE i.coupon_issue_id IS NULL
UNION ALL
SELECT 'coupon_stock.coupon_id', s.coupon_stock_id
FROM coupon_stock s LEFT JOIN coupon c ON c.coupon_id = s.coupon_id
WHERE c.coupon_id IS NULL
```

</details>

### R7. `TOOL_RELIABILITY` — 도구 신뢰성

**위반을 일부러 주입했을 때 규칙이 실제로 검출하는가.**

`R1`·`R3`은 구조상 위반이 나올 수 없어, 0건 반환만으로는 아무것도 증명하지 못한다. 검출 능력을 먼저 보여야 "불일치 0건"이 주장으로 성립한다.

| 항목 | 값 |
| --- | --- |
| 실행 환경 | Testcontainers 등 격리된 DB **전용** |
| 시나리오 | 규칙별 최소 1개, 주입 건수를 미리 정함 |
| 통과 조건 | 검출 건수 = 주입 건수 |
| `checked_count` | 시나리오 수 |
| `violation_count` | 검출에 실패한 시나리오 수 |

> **운영·로컬 공용 DB에서는 실행하지 않는다.** 위반 데이터를 실제로 INSERT하므로 검증 대상을 오염시킨다.

<details>
<summary><b>DB 제약 우회 방법 — 규칙마다 다르다</b></summary>

| 규칙 | 막는 것 | 주입 방법 |
| --- | --- | --- |
| `R1` `DUPLICATE_ISSUE` | `uk_issue_coupon_member` | 주입 전용 스키마에서 해당 인덱스를 뺀 상태로 시험 |
| `R6` `ORPHAN_REFERENCE` | FK 제약 | `SET FOREIGN_KEY_CHECKS = 0` 후 INSERT |
| `R2`·`R3`·`R4`·`R5` | 없음 | 그대로 INSERT / UPDATE |

`SET FOREIGN_KEY_CHECKS = 0`은 **FK만 우회하고 유니크 인덱스는 우회하지 못한다.**

</details>

### R8. `REDIS_DB_MISMATCH` — Redis·DB 교차 정합성

Redis가 확정한 발급 결과와 DB 이력이 같은가.

검사 대상은 **`coupon.status = 'OPEN'`인 쿠폰뿐**이다. Redis 키는 발급을 여는 쿠폰에만 만들어지므로, 지난 회차까지 포함하면 "키가 없다"가 전부 위반으로 잡힌다.

#### 선행 조건 — 동기화가 끝나야 판정할 수 있다

발급은 Redis에서 확정되고 DB 반영은 Stream 컨슈머가 뒤따르므로, 발급이 진행 중인 동안에는 차이가 있는 것이 정상이다. 아래 둘 중 하나라도 남아 있으면 위반 0건이 아니라 **실행 실패(판정 불가)** 로 기록한다.

| 확인 | 방법 |
| --- | --- |
| 아직 DB로 안 넘어간 발급 | `XLEN coupon:{id}:issue-stream` > 0 |
| 컨슈머가 처리 중인 발급 | `XPENDING`의 미확인 건수 > 0 |

컨슈머가 DB 커밋 뒤 `XACK`과 `XDEL`을 함께 하므로, 둘 다 0이면 처리가 끝난 것이다. 사람에게 "부하 테스트 끝났나요"라고 묻지 않고 스트림에서 직접 확인한다.

#### 위반 판정

| 코드 | 조건 | 뜻 |
| --- | --- | --- |
| `ISSUED_ONLY_IN_REDIS` | Redis 발급 집합 − DB 발급 회원 | DB 적재가 유실됐다 |
| `ISSUED_ONLY_IN_DB` | DB 발급 회원 − Redis 발급 집합 | 발급하지 않은 회원의 이력이 있다 |
| `STOCK_COUNT_MISMATCH` | Redis 재고 ≠ `coupon_stock.remaining_quantity` | 재고가 어긋났다 |

동기화가 끝난 상태를 전제하므로 **양방향 모두 위반이다.** 한쪽만 보면 유실 방향을 놓친다.

> **Redis 키가 아예 없는 것도 `STOCK_COUNT_MISMATCH`다.** 쿠폰이 `OPEN`인데 재고 키가 없으면 발급 요청이 전건 거부되므로, DB는 열려 있다는데 실제로는 아무도 받을 수 없는 상태다. 초기화를 안 했거나 키가 유실된 것이며 둘 다 위반이다.

| 항목 | 값 |
| --- | --- |
| `checked_count` | 쿠폰별 (Redis 발급 회원 수 + DB 발급 회원 수 + **재고 대조 1**) 의 합 |
| `target_type` | `COUPON_MEMBER_PAIR`(회원 차집합) / `COUPON`(재고) |
| `detail` | 코드와 함께 어긋난 값 |

위반 상세는 회원 번호 오름차순으로 담는다. 상한(5절)에 걸려 잘려도 매번 같은 표본이 남아야 재현성이 유지된다.

---

## 4. 판정

```
규칙 중 하나라도 실행 실패  →  verdict = ERROR   이 실행은 신뢰할 수 없다
위반이 하나라도 있음        →  verdict = FAIL
전부 검사 완료, 위반 0건    →  verdict = PASS
```

| 판정 | 뜻 | 화면에서 |
| --- | --- | --- |
| `PASS` | 전 규칙 검사 완료, 불일치 0건 | 정상 |
| `FAIL` | 위반을 찾았다 | 위반 |
| **`ERROR`** | **판정을 내릴 수 없다** | **정상으로 표시하면 안 된다** |

> `ERROR`는 위반이 없다는 뜻이 **아니다.** 규칙 하나가 죽으면 그 실행의 "불일치 0건"은 주장으로 성립하지 않는다.

**실행에 실패한 규칙은 위반 수가 0이지만 통과가 아니다.** 판정에서 통과로 세지 않고, 리포트에도 구분해 표시한다. 규칙 하나가 실패해도 나머지는 계속 실행한다.

실패한 규칙은 `verification_rule_result.status = 'FAILED'`와 `failure_reason`으로 남긴다. 남기지 않으면 `checked_count = 0, violation_count = 0`이 되어, 검사 대상이 없어 0인 정상 실행과 구분되지 않는다.

---

## 5. 위반 상세 저장

`violation_count`에는 **전체 위반 수**를 기록하고, `verification_violation` 행은 **규칙당 최대 1000건**만 남긴다.

**상한에는 결정론적 정렬을 함께 건다.**

```sql
... ORDER BY coupon_issue_id LIMIT 1000
```

> `ORDER BY` 없이 `LIMIT`만 쓰면 반환되는 1000건이 실행마다 달라져, 집계가 같아도 상세 목록이 바뀐다(`NFR-3` 위반).

---

## 6. 알려진 제약

| 항목 | 내용 |
| --- | --- |
| 구조상 통과하는 규칙 | `R1`·`R3`은 현재 데이터에서 위반이 나올 수 없다. `R7`이 이를 보완한다 |
| 유예 시간 결합 | `G`는 만료 배치 주기(`fixed-delay-ms`)에서 파생한다. 검증기가 이 설정을 읽어야 하므로 B2 설정에 대한 의존이 생긴다. 배치 주기를 검증기가 알 수 없는 환경(별도 프로세스 실행 등)에서는 유예를 명시적으로 넘겨야 한다 |
| 실행계획 미확인 | `EXPLAIN` 분석은 아직이다. `R5`의 `BROKEN_CHAIN`이 가장 무겁다 — 600만 행을 발급 건별로 정렬해야 해서 나머지 규칙을 전부 합친 것보다 오래 걸린다 |
| `R8` 선행 조건 | 발급이 진행 중이면 `R8`은 판정 불가로 남고 전체 판정이 `ERROR`가 된다. 부하 테스트가 끝나 스트림이 비워진 뒤 실행해야 한다 |
