# B1 정합성 검증 규칙 명세

`com.mocou.consistency`가 검사하는 규칙과, 각 규칙이 **무엇을 위반으로 보는지**를 확정한다.

판정식이 코드에만 있으면 "이건 위반이 아니라 정상인데?" 논쟁이 났을 때 근거가 없다. 데이터 규격은 [B1 더미데이터 규격과 적재 가이드](./datagen.md)에 있으며, 이 문서는 그 규격을 "정상"의 정의로 삼는다.

관련 요구사항: `FR-3.1`~`FR-3.5`, `NFR-2`, `NFR-3`

---

## 1. 원칙

**검출하고 기록할 뿐, 고치지 않는다.** 고치면 재실행 결과가 달라지고(`NFR-3` 위반) 원인 조사 증거가 사라진다.

**절대 건수가 아니라 불변식으로 판정한다.** `ISSUED가 14,000건`이 아니라 `ISSUED인 건은 expires_at > T`처럼 쓴다. 앞의 형태는 만료 배치가 도는 순간 전건 실패한다.

**검증 대상은 발급 이력 300만 건 전체다**(`FR-3.4`). 표본 검사나 최근 N건 검사는 하지 않는다.

---

## 2. 실행 단위와 기준 시각

검증 1회가 `verification_run` 1행이다. 규칙별 집계는 `verification_rule_result`, 위반 상세는 `verification_violation`에 남는다.

### 2.1 기준 시각 확보

**모든 규칙 쿼리를 하나의 읽기 전용 `REPEATABLE READ` 트랜잭션에서 실행한다.** 규칙별로 트랜잭션을 나누면 그 사이 만료 배치가 상태를 바꿔, 규칙마다 다른 시점의 데이터를 보게 된다.

`verification_run.snapshot_at`에는 트랜잭션 시작 직후 읽은 DB 시각을 기록한다. 판정식의 `T`가 이 값이다. 판정 대상 시각이 전부 DB가 찍은 값이므로 애플리케이션 시각은 쓰지 않는다.

트레이드오프로 긴 읽기 트랜잭션이 undo log purge를 지연시킨다. 문제가 되면 `snapshot_at` 컬럼 필터 방식으로 전환한다.

### 2.2 검증 실행 기록

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

`checked_count`는 검사 대상 수, `violation_count`는 위반 수다. **규칙이 실행에 실패하면 둘 다 0이 되는데, 검사 대상이 없어 0인 정상 실행과 값이 같다.** 둘을 구분하는 실행 상태는 따로 기록한다.

### R1. `DUPLICATE_ISSUE` — 중복 발급

한 회원이 같은 쿠폰을 2장 이상 받았는가 (`FR-2.3`).

```sql
SELECT coupon_id, member_id, COUNT(*) AS issue_count
FROM coupon_issue
GROUP BY coupon_id, member_id
HAVING COUNT(*) > 1
```

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_issue` 전체 행 수 |
| `target_type` | `COUPON_MEMBER_PAIR` |
| `target_id` / `target_id2` | `coupon_id` / `member_id` |

`GROUP BY (coupon_id, member_id)`는 `uk_issue_coupon_member`의 컬럼 순서와 같아 인덱스만으로 집계된다.

**현재 구조에서는 위반이 나올 수 없다.** 유니크 인덱스가 INSERT 단계에서 막는다. 제약이 살아 있는지 확인하는 용도이며, Redis→DB 비동기 동기화 경로(#39)가 붙으면 검출 대상이 생긴다.

### R2. `OVER_ISSUE` — 초과 발급

쿠폰별 발급 건수가 총 재고를 넘었는가 (`FR-2.1`, `NFR-1`).

```sql
SELECT s.coupon_id, s.total_quantity, COUNT(i.coupon_issue_id) AS issued
FROM coupon_stock s
LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
GROUP BY s.coupon_id, s.total_quantity
HAVING issued > s.total_quantity
```

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_stock` 행 수 |
| `target_type` | `COUPON` |
| `target_id` | `coupon_id` |
| `detail` | `총재고 10000, 발급 10001` |

`LEFT JOIN`이어야 한다. `INNER JOIN`으로 바꾸면 발급 이력이 없는 시연 회차가 검사 범위에서 조용히 빠진다.

초과 발급을 막는 DB 제약은 없다. 재고 차감이 애플리케이션·Redis 책임이라 실제로 위반이 검출될 수 있다.

### R3. `STOCK_MISMATCH` — 이력·재고 정합성

`총재고 = 발급 건수 + 잔여 재고`가 성립하는가 (`FR-3.3`, `NFR-2`).

```sql
SELECT s.coupon_id, s.total_quantity, s.remaining_quantity, COUNT(i.coupon_issue_id) AS issued
FROM coupon_stock s
LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
GROUP BY s.coupon_id, s.total_quantity, s.remaining_quantity
HAVING s.total_quantity <> s.remaining_quantity + issued
```

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_stock` 행 수 |
| `target_type` | `COUPON` |
| `detail` | `총재고 10000, 발급 9998, 잔여 1` |

여기서도 `LEFT JOIN`이 필수다. `INNER JOIN`으로 바꾸면 시연 회차가 검사되지 않는데, 미검사와 통과가 결과에서 구분되지 않는다.

**적재 직후에는 위반이 나올 수 없다.** `remaining_quantity`를 발급 건수로 역산해 채우므로 정의상 성립한다. 발급 경로가 `remaining_quantity`를 갱신하지 않으면 부하 테스트 직후 검출된다.

### R4. `STATE_TIMESTAMP_MISMATCH` — 상태·시각 모순

`coupon_issue` 한 행 안에서 상태와 시각이 서로 어긋나는가 (`FR-3.6`).

8개 항목을 검사한다. `T`는 `snapshot_at`이다.

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

한 행이 여러 항목을 동시에 어길 수 있다. `violation_count`는 **위반 행 수**로 세고, `detail`에는 그 행이 어긴 항목을 모두 나열한다.

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

`CONCAT_WS`는 `NULL` 인자를 건너뛰므로, 조건에 걸리지 않은 항목은 자동으로 빠진다.

필터는 `WHERE`에 쓴다. `SELECT` 별칭에 `HAVING`을 걸면 `GROUP BY`가 없어 전체가 한 그룹으로 묶이고 **최대 1행만 반환된다.**

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_issue` 전체 행 수 |
| `target_type` | `COUPON_ISSUE` |
| `target_id` | `coupon_issue_id` |
| `detail` | 위반 항목 코드 목록 |

**유예 `G`**

```
G = mocou.lifecycle.expiration.fixed-delay-ms × 5
```

만료 전환은 배치가 일괄 처리하므로(`FR-2.6`) 만료 시각과 상태 전환 사이에 지연이 있다. 정상 지연은 최대 2주기다. 상수로 두면 배치 주기를 조정했을 때 함께 어긋나므로 배수만 고정한다.

`ISSUED_BEFORE_SIGNUP`은 300만 행 각각에 `member` PK 조회가 발생한다. 비용이 크면 이 항목만 별도 쿼리로 분리한다.

### R5. `HISTORY_MISMATCH` — 이력 체인 정합성

상태 이력이 현재 상태와 일치하고, 체인이 끊기지 않았는가 (`FR-3.1`).

R4가 한 행 안의 모순을 보는 반면 이 규칙은 `coupon_issue`와 `coupon_issue_history`의 관계를 본다. 약 600만 행을 조인·정렬하므로 비용도 성격도 달라 규칙을 분리한다.

| 코드 | 조건 |
| --- | --- |
| `MISSING_INITIAL_HISTORY` | `UNISSUED → ISSUED` 이력이 정확히 1건이 아님 |
| `FINAL_STATUS_MISMATCH` | 마지막 이력의 `to_status ≠ coupon_issue.status` |
| `BROKEN_CHAIN` | 직전 이력의 `to_status ≠ 다음 이력의 from_status` |
| `HISTORY_BEFORE_ISSUE` | 이력의 `changed_at < coupon_issue.issued_at` |

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

| 항목 | 값 |
| --- | --- |
| `checked_count` | `coupon_issue` 전체 행 수 |
| `target_type` | `COUPON_ISSUE` |
| `target_id` | `coupon_issue_id` |

정렬에 `history_id`를 함께 넣는다. `changed_at`이 같은 이력이 있을 때 순서가 흔들리면 판정이 실행마다 달라진다.

`uk_history_issue_idempotency`의 선두 컬럼이 `coupon_issue_id`라 파티션 단위 접근에는 도움이 되지만, `changed_at` 정렬에서 filesort가 발생할 수 있다. `EXPLAIN`으로 확인하고 결과를 [실행계획 분석] 항목에 기록한다.

### R6. `ORPHAN_REFERENCE` — 참조 무결성

존재하지 않는 회원·쿠폰·발급 건을 가리키는 행이 있는가.

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

| 항목 | 값 |
| --- | --- |
| `checked_count` | 검사한 참조 관계의 행 수 합 |
| `target_type` | `COUPON_ISSUE` / `COUPON_ISSUE_HISTORY` / `COUPON_STOCK` |
| `detail` | 어느 참조가 끊겼는지 (`coupon_issue.member_id` 등) |

FK가 걸려 있어도 검사한다. `SET FOREIGN_KEY_CHECKS = 0`으로 적재하는 경로(초기화 스크립트, `mysqldump` 복원)가 있어 고아 행이 만들어질 수 있다.

**`issue_failure_log`는 검사 대상에서 제외한다.** `COUPON_NOT_FOUND`를 기록하려면 존재하지 않는 ID도 넣을 수 있어야 해 FK를 걸지 않은 테이블이다. 포함하면 정상 데이터가 전부 위반으로 잡힌다.

### R7. `TOOL_RELIABILITY` — 도구 신뢰성

**위반을 일부러 주입했을 때 규칙이 실제로 검출하는가.**

R1·R3은 구조상 위반이 나올 수 없어, 0건 반환만으로는 아무것도 증명하지 못한다. 검출 능력을 먼저 보여야 "불일치 0건"이 주장으로 성립한다.

| 항목 | 값 |
| --- | --- |
| 실행 환경 | Testcontainers 등 격리된 DB **전용** |
| 시나리오 | 규칙별 최소 1개, 주입 건수를 미리 정함 |
| 통과 조건 | 검출 건수 = 주입 건수 |
| `checked_count` | 시나리오 수 |
| `violation_count` | 검출에 실패한 시나리오 수 |

**운영·로컬 공용 DB에서는 실행하지 않는다.** 위반 데이터를 실제로 INSERT하므로 검증 대상을 오염시킨다.

DB 제약이 막는 규칙은 제약을 우회해 주입해야 하는데, 우회 방법이 규칙마다 다르다.

| 규칙 | 막는 것 | 주입 방법 |
| --- | --- | --- |
| R1 `DUPLICATE_ISSUE` | `uk_issue_coupon_member` | 주입 전용 스키마에서 해당 인덱스를 뺀 상태로 시험 |
| R6 `ORPHAN_REFERENCE` | FK 제약 | `SET FOREIGN_KEY_CHECKS = 0` 후 INSERT |
| R2·R3·R4·R5 | 없음 | 그대로 INSERT / UPDATE |

`SET FOREIGN_KEY_CHECKS = 0`은 FK만 우회하고 유니크 인덱스는 우회하지 못한다.

---

## 4. 판정

```
규칙 중 하나라도 실행 실패  →  verdict = ERROR   (이 실행은 신뢰할 수 없다)
위반이 하나라도 있음        →  verdict = FAIL
전부 검사 완료, 위반 0건    →  verdict = PASS
```

`ERROR`는 위반이 없다는 뜻이 아니라 **판정을 내릴 수 없다**는 뜻이다. 규칙 하나가 죽으면 그 실행의 "불일치 0건"은 주장으로 성립하지 않는다.

실패한 규칙은 `verification_rule_result`에 실행 상태와 사유를 함께 남긴다. 남기지 않으면 `checked_count = 0, violation_count = 0`이 되어, 검사 대상이 없어 0인 정상 실행과 구분되지 않는다.

**실행에 실패한 규칙은 위반 수가 0이지만 통과가 아니다.** 판정에서 통과로 세지 않고, 리포트에도 구분해 표시한다. 규칙 하나가 실패해도 나머지는 계속 실행한다.

---

## 5. 위반 상세 저장

`violation_count`에는 **전체 위반 수**를 기록하고, `verification_violation` 행은 **규칙당 최대 1000건**만 남긴다.

**상한에는 결정론적 정렬을 함께 건다.**

```sql
... ORDER BY coupon_issue_id LIMIT 1000
```

`ORDER BY` 없이 `LIMIT`만 쓰면 반환되는 1000건이 실행마다 달라져, 집계가 같아도 상세 목록이 바뀐다(`NFR-3` 위반).

---

## 6. 보류: `REDIS_DB_MISMATCH`

Redis 발급 집합과 DB 이력의 양방향 차집합을 본다. **A팀 키 스펙이 확정되어야 착수할 수 있어 이번 명세에서는 판정식을 정하지 않는다.**

필요한 것:

| 항목 | 왜 |
| --- | --- |
| 발급 회원 집합 키 패턴·자료구조 | 차집합 대상을 특정하기 위해 |
| 재고 카운터 키 패턴 | Redis 잔여와 DB 잔여 비교 |
| 발급 시 `coupon_stock.remaining_quantity` 갱신 여부 | 갱신하지 않으면 R3이 부하 테스트 직후 위반을 검출한다 |

스펙이 나오면 판정식과 함께, 실행하지 못한 규칙을 결과에 어떻게 표현할지도 같이 정한다. `verification_run.verdict`에는 현재 `PASS` / `FAIL`만 있다.

---

## 7. 알려진 제약

| 항목 | 내용 |
| --- | --- |
| 구조상 통과하는 규칙 | R1·R3은 현재 데이터에서 위반이 나올 수 없다. R7이 이를 보완한다 |
| 유예 시간 결합 | `G`는 만료 배치 주기(`fixed-delay-ms`)에서 파생한다. 검증기가 이 설정을 읽어야 하므로 B2 설정에 대한 의존이 생긴다. 배치 주기를 검증기가 알 수 없는 환경(별도 프로세스 실행 등)에서는 유예를 명시적으로 넘겨야 한다 |
| 실행계획 미확인 | 판정식 초안은 실데이터 300만 건에 실행해 전 규칙 0건을 확인했으나, `EXPLAIN` 분석은 아직이다. R5의 `BROKEN_CHAIN`이 가장 무겁다 — 600만 행을 발급 건별로 정렬해야 해서 나머지 규칙을 전부 합친 것보다 오래 걸린다 |
