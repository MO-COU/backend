# B1 더미데이터 규격과 적재 가이드

`com.mocou.datagen`이 만드는 데이터가 무엇인지, 어떻게 적재하고 무엇으로 확인하는지 기록한다.

정합성 검증(`com.mocou.consistency`)은 여기 적힌 규격을 "정상"의 정의로 삼는다. 검증 규칙이 무엇을 위반으로 볼지가 이 문서에서 결정되므로, 생성 정책을 바꾸면 검증 규칙도 함께 봐야 한다.

관련 요구사항: `FR-1.1.1`, `FR-1.2`, `FR-1.3`, `NFR-3`, `NFR-6`

---

## 1. 적재 결과 요약

기본 설정으로 실행했을 때 들어가는 행 수다. 모두 사전 계산으로 정확히 예측 가능한 값이며, 실행할 때마다 같아야 한다.

| 테이블 | 행 수 | 비고 |
| --- | ---: | --- |
| `member` | 1,000,000 | `FR-1.1.1` 수용 기준 |
| `coupon` | 301 | 과거 회차 300 + 부하테스트 시연용 1 |
| `coupon_stock` | 301 | 쿠폰과 1:1 |
| `coupon_issue` | 3,000,000 | `FR-1.2` 수용 기준 |
| `coupon_issue_history` | 5,986,000 | 발급 건당 1~2행 |

`coupon_issue` 상태 분포:

| 상태 | 건수 | 비율 |
| --- | ---: | ---: |
| `USED` | 1,794,000 | 59.80% |
| `EXPIRED` | 1,192,000 | 39.73% |
| `ISSUED` | 14,000 | 0.47% |

`ISSUED`가 적은 것은 의도된 결과다. 쿠폰 유효기간이 발급일로부터 14일(`FR-2.6`)이고 회차는 매주 열리므로, **유효기간이 남아 있는 회차는 마지막 2개뿐**이다. 나머지 298개 회차에는 `ISSUED`가 존재할 수 없다. 쓰지 않았다면 이미 만료되었기 때문이다.

---

## 2. 회차 모형

`coupon` 한 행이 회차 하나다. 매주 월요일 10시에 같은 쿠폰을 다시 여는 서비스를 가정한다.

```
1회차 ─── 2회차 ─── ... ─── 299회차 ─ 300회차 ┊ 301(시연용)
 │                                        │    │
 └─ 기준 시각 기준 300주 전               │    └─ OPEN, 발급 이력 없음
                                          └─ 기준 시각 직전 월요일
```

**왜 회차로 나누는가.** `coupon_issue`에 `UNIQUE (coupon_id, member_id)`가 걸려 있어 한 쿠폰당 한 회원은 1건이 상한이다. 회원이 100만 명이므로 쿠폰이 한 종이면 이력은 최대 100만 건이고, 요구 수량 300만 건을 만들 수 없다. 회차를 나누면 상한이 `회차 수 × 회차당 재고`로 늘어난다. 300회차 × 10,000장 = 300만 건이며, **모든 회차가 매진된다는 전제**에서 요구 수량이 정확히 나온다.

회원 수를 300만으로 늘려 쿠폰 한 종으로 해결하는 방법도 있으나, 요구사항이 회원 100만(`FR-1.1.1`)과 이력 300만(`FR-1.2`)을 동시에 고정하고 있어 선택지가 아니다.

**시연용 회차(301)는 격자에서 뺀다.** 진짜 다음 월요일을 오픈 시각으로 두면 월요일이 아닌 날 부하 테스트를 돌릴 때 `NOT_OPEN_YET`으로 전건 거부된다. 그래서 기준 시각 하루 전에 열린 것으로 만들고 1년 뒤에 닫는다. 이 회차에는 발급 이력을 만들지 않으므로, **A팀이 부하 테스트를 몇 번 반복하든 정합성 검증 대상인 300만 건은 변하지 않는다.**

---

## 3. 생성 규칙

### 3.1 회원 (`MemberGenerator`)

모든 필드를 회원 번호에서 계산한다. 난수 생성기를 쓰지 않으므로 적재를 여러 스레드로 나눠도 같은 번호는 항상 같은 값을 갖는다. 난수 생성기 하나를 공유하면 스레드 스케줄링에 따라 값의 배분이 달라져 재현성이 깨진다.

| 필드 | 규칙 | 예시 |
| --- | --- | --- |
| `member_id` | 1부터 연속 | `1` |
| `email` | `user%08d@mocou.test` | `user00000001@mocou.test` |
| `name` | 성 10종 × 이름 앞 10종 × 이름 뒤 10종 | `김서준` |
| `phone` | `010-%04d-%04d` | `010-1234-5678` |
| `created_at` | 첫 회차 오픈 시각의 1~365일 전 | — |

이름·연락처는 회원 번호에 `2654435761`을 곱해 자릿수를 흩뜨린 값에서 뽑는다. 연속된 번호가 비슷한 이름으로 몰리지 않게 하려는 것이다. 형식은 C팀 마스킹 유틸(`global.masking.MaskingUtils`)이 처리할 수 있는 모양에 맞췄다.

**가입일 상한이 첫 회차 오픈 시각인 이유**는 가입하지도 않은 회원이 쿠폰을 받은 이력이 생기면 안 되기 때문이다(`member.created_at > coupon_issue.issued_at`은 정합성 위반이다).

### 3.2 당첨자 선정 (`IssueAllocator`)

회차 안에서 순번 `order`(1..10,000)를 회원 번호로 옮긴다.

```
member_id = ((order × 618041 + roundOffset) % 1000000) + 1
roundOffset = (round × 7919) % 1000000
```

`618041`이 회원 수와 서로소이므로 **순번이 다르면 회원 번호도 반드시 다르다.** 회차당 1인 1매를 코드가 보장하고, `UNIQUE (coupon_id, member_id)`가 그 결과를 DB에서 다시 확인한다. 회원 수를 `618041`의 배수로 설정하면 이 성질이 깨지므로 기동 시 최대공약수를 검사해 중단한다.

난수로 뽑고 충돌하면 다시 뽑는 방식은 쓰지 않았다. 재시도 횟수가 실행마다 달라지면 난수 소비량이 흔들려 재현성이 깨지기 때문이다.

**보폭이 `618041`인 이유.** 서로소는 충돌만 막을 뿐 당첨자가 잘 흩어지는지는 보장하지 않는다. 보폭이 회원 수의 간단한 분수 배수(1/2, 1/3 …)에 가까우면 금방 제자리로 돌아와 특정 구간에 몰린다. `618041`은 회원 100만 기준 황금비(0.618…)에 가장 가까운 소수이며, 황금비는 어떤 분수로도 잘 근사되지 않아 가장 늦게까지 뭉치지 않는다.

회차마다 `roundOffset`으로 시작점을 옮겨 당첨자 명단이 달라진다. 회원 100만 명에 발급 300만 건이므로 **회원 한 명이 평균 3회 당첨**되며, 실제 분포는 2~4회다.

### 3.3 발급 시각

```
issued_at = openAt + (order × 30ms), 초 단위 절삭
```

선착순이므로 순번이 곧 도착 순서다. 30ms 간격이면 1만 번째가 오픈 5분 뒤에 들어온다. 초 단위로 절삭하므로 같은 초에 여러 건이 몰리는데, 실제 선착순 이벤트의 모양과 다르지 않다.

기준 시각까지 5분이 남지 않은 경우(오픈 직후에 데이터를 만드는 경우) 간격을 좁혀 **미래에 발급된 행이 생기지 않게** 한다.

### 3.4 상태 배분

순번으로 자른다. 난수로 던지면 비율이 요청한 값에서 흔들리는데, 순번으로 자르면 정확히 맞고 재현성도 함께 얻는다.

| 회차 종류 | 판정 | `USED` | 나머지 |
| --- | --- | ---: | --- |
| 유효기간 남음 | `openAt + 14일 > 기준시각` | 30% | `ISSUED` |
| 유효기간 지남 | 그 외 | 60% | `EXPIRED` |

`used_at`은 발급 시각과 만료 시각 사이의 임의 시점이다. **계산으로 복원할 수 없는 유일한 값이라 난수를 여기 한 곳에서만 쓴다.** 시드는 `seed + 회차번호`로 유도해, 회차를 어떤 순서로 처리하든 같은 결과가 나온다.

### 3.5 상태 이력 (`coupon_issue_history`)

발급 한 건이 상태에 따라 1~2행이 된다.

| 최종 상태 | 이력 행 |
| --- | --- |
| `ISSUED` | `UNISSUED → ISSUED` |
| `USED` | `UNISSUED → ISSUED`, `ISSUED → USED` |
| `EXPIRED` | `UNISSUED → ISSUED`, `ISSUED → EXPIRED` |

행이 없다가 생기는 것도 한 번의 상태 변화이므로 최초 발급 이력의 `from_status`를 `UNISSUED`로 명시한다. `NULL`을 허용하면 규약을 어겨도 DB가 막지 못하고, 검증 쿼리의 조인에서 조용히 빠진다(V5 마이그레이션).

발급과 최초 이력은 **같은 트랜잭션**에서 적재한다. 발급만 들어가고 이력이 빠지면 그 자체로 정합성 위반 데이터가 된다.

`idempotency_key`는 `ISSUE:{issueId}` / `USE:{issueId}` / `EXPIRE:{issueId}:{expiresAt}` 형식이며, `UNIQUE (coupon_issue_id, idempotency_key)`가 같은 전이의 중복 기록을 막는다.

### 3.6 재고 역산 (`StockReconciler`)

쿠폰을 만드는 시점에는 몇 건이 발급될지 모르므로 `remaining_quantity`를 `total_quantity`와 같게 넣어 두고, 발급을 다 적재한 뒤 실제로 들어간 행을 세어 채운다.

```sql
UPDATE coupon_stock s
JOIN (SELECT coupon_id, COUNT(*) AS issued FROM coupon_issue GROUP BY coupon_id) t
  ON t.coupon_id = s.coupon_id
SET s.remaining_quantity = s.total_quantity - t.issued
```

미리 0으로 박아 두는 방법도 있지만, 그러면 "재고를 다 소진할 것"이라는 가정을 쿠폰 생성 쪽과 배분 쪽이 함께 믿어야 한다. 한쪽만 바뀌면 조용히 어긋나고 적재가 일부 실패해도 흔적이 남지 않는다. 실제로 들어간 행을 세면 그런 이상이 잔여 재고에 그대로 드러난다.

`GROUP BY coupon_id`는 `UNIQUE (coupon_id, member_id)`의 선두 컬럼을 따라가므로 인덱스만으로 집계된다.

발급 이력이 없는 시연 회차는 집계에 나타나지 않아 조인에서 빠지고, 재고 10,000장이 온전히 남는다.

> **주의:** 이 방식 때문에 적재 직후에는 `total = 발급 + 잔여`가 정의상 항상 성립한다. 즉 재고 정합성 규칙은 datagen 데이터만으로는 위반을 만들 수 없고, A팀 발급 경로가 붙은 뒤에야 의미를 갖는다.


### 3.7 시연용 쿠폰 Redis 초기화 (`CouponRedisInitializationService`)

Redis Lua 기반 발급 경로는 요청마다 MySQL을 조회하지 않는다. 따라서 더미데이터 적재가 끝나면 시연용 `OPEN` 쿠폰의 최종 재고와 발급 가능 시간을 Redis에 미리 저장한다.

```text
CouponSeeder
→ MemberGenerator
→ IssueGenerator
→ StockReconciler
→ CouponRedisInitializationService
```

`StockReconciler`가 실제 발급 이력을 기준으로 DB 잔여 재고를 확정한 뒤 Redis를 초기화한다. 초기화 기준값의 원본은 MySQL이며 Redis는 발급 요청을 빠르게 처리하기 위한 복사본이다.

| Redis Key | 자료형 | 저장 값 |
| --- | --- | --- |
| `coupon:{couponId}:stock` | String | `coupon_stock.remaining_quantity` |
| `coupon:{couponId}:metadata` | Hash | `openAtEpochSecond`, `closeAtEpochSecond` |

발급 시간은 MySQL의 coupon.open_at, coupon.close_at을 Asia/Seoul 기준 Epoch Second로 변환해 저장한다. 과거 300개 회차는 부하 테스트 대상이 아니므로 초기화하지 않고, 시연용 회차 하나만 초기화한다.
재고 Key와 Metadata Key는 Lua Script 하나에서 원자적으로 생성한다.

| 초기화 결과 | 처리 |
| --- | --- |
| `INITIALIZED` | 두 Key를 새로 생성하고 계속 진행 |
| `ALREADY_INITIALIZED` | 발급으로 차감됐을 수 있는 기존 재고를 덮어쓰지 않고 계속 진행 |
| `INCONSISTENT_STATE` | 두 Key 중 하나만 존재하는 비정상 상태이므로 실행 중단 |

DB 데이터가 이미 존재하면 대용량 데이터 생성은 건너뛰지만 Redis 초기화는 다시 확인한다. Redis 재시작이나 데이터 유실로 Key가 사라진 경우 DB 값을 기준으로 복구하기 위해서다.

---

## 4. 실행 방법

### 4.1 사전 준비

```bash
docker compose up -d
```

MySQL(3306) · Redis(6379)가 뜨고 Flyway가 스키마를 적용한다. `docker compose ps`의 PORTS에 `0.0.0.0:3306->3306/tcp` 화살표가 있는지 확인한다. `healthy`는 컨테이너 내부 상태라 호스트 포트 포워딩 누락을 잡지 못한다.

### 4.2 적재 실행

`datagen` 프로필로 기동할 때만 동작한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,datagen'
```

HTTP로 열지 않은 이유는, 요청 한 번이 수백만 행 적재를 유발하고 중복 실행 시 `UNIQUE (coupon_id, member_id)`에 걸려 **데이터가 절반만 들어간 상태로 남기** 때문이다.

`member` 또는 `coupon`에 행이 있으면 대용량 데이터 생성은 건너뛴다. 다만 Redis 재시작 등으로 시연용 쿠폰 Key가 사라질 수 있으므로 Redis 초기화 상태는 다시 확인한다. DB 데이터까지 다시 만들려면 4.4의 초기화가 먼저다.

기본 설정(회원 100만 + 발급 300만)은 맥 + Docker 환경에서 **7~11분** 걸린다. 중간에 멈춘 것처럼 보여도 회차별 진행 로그가 계속 찍히므로 그것으로 확인한다.

앱이 이미 8080 포트에서 돌고 있으면 기동에 실패한다. datagen은 HTTP를 쓰지 않으므로 `--server.port=0`을 붙이면 포트를 비우지 않고도 실행할 수 있다.

### 4.3 파라미터

`mocou.datagen.*` 접두사를 쓴다.

| 키 | 기본값 | 설명 |
| --- | ---: | --- |
| `member-count` | 1000000 | 회원 수 |
| `round-count` | 300 | 과거 회차 수 |
| `round-stock` | 10000 | 회차당 재고 |
| `demo-coupon-total-quantity` | 10000 | 시연 회차 재고 |
| `base-time` | (DB 현재 시각) | 기준 시각 T0 |
| `seed` | 20260819 | 난수 시드 |
| `chunk-size` | 10000 | 회원 적재 트랜잭션 단위 |

소규모로 빠르게 확인할 때:

```bash
./gradlew bootRun --args='--spring.profiles.active=local,datagen --mocou.datagen.member-count=10000 --mocou.datagen.round-count=3'
```

> **재현성 주의:** `base-time`을 지정하지 않으면 DB 현재 시각을 쓴다. 실행 시각이 달라지면 회차 격자 전체가 이동하고 상태 분포도 달라진다. **같은 데이터를 다시 만들어야 한다면 `base-time`을 반드시 고정한다.** 시드만 같아서는 재현되지 않는다.

### 4.4 초기화

FK가 참조하는 테이블은 `TRUNCATE`가 거부되므로(`ERROR 1701`) 검사를 끄고 자식부터 지운다.

**검증 실행 기록도 함께 지운다.** 데이터를 통째로 다시 만들면 그 데이터를 대상으로 한 검증 결과는 의미가 없고, `verification_run.issue_run_id`가 `coupon_issue_run`을 참조하므로 남겨두면 끊어진 참조가 된다.

```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE verification_violation;
TRUNCATE TABLE verification_rule_result;
TRUNCATE TABLE verification_run;
TRUNCATE TABLE coupon_issue_run;
TRUNCATE TABLE coupon_issue_history;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE notification;
TRUNCATE TABLE issue_failure_log;
TRUNCATE TABLE coupon_stock;
TRUNCATE TABLE coupon;
TRUNCATE TABLE member;
SET FOREIGN_KEY_CHECKS = 1;
```

검증 기록을 보존해야 한다면 `coupon_issue_run`을 지우지 않는 부분 삭제 절차를 따로 써야 한다. 위 스크립트는 전체 초기화 전용이다.

`FOREIGN_KEY_CHECKS = 0`은 **세션 변수**다. 다른 커넥션에는 영향이 없고 이 스크립트를 실행한 세션에서만 유효하며, 켜져 있는 동안에는 부모 테이블을 지워도 막히지 않는다. 순서를 지키지 않으면 에러 없이 고아 행이 남는다.

로컬 Redis도 함께 초기화해야 다음 datagen 실행에서 DB 값을 다시 반영할 수 있다. 아래 명령은 현재 Redis DB의 모든 Key를 삭제하므로 로컬 전용 환경에서만 실행한다.

```bash
docker compose exec redis redis-cli FLUSHDB
```

Redis를 초기화하지 않으면 기존 재고를 보호하기 위해 ALREADY_INITIALIZED가 반환되며, DB를 다시 생성해도 Redis 재고를 덮어쓰지 않는다.

---

## 5. 적재 후 확인

```sql
-- 행 수
SELECT 'member' AS t, COUNT(*) FROM member
UNION ALL SELECT 'coupon', COUNT(*) FROM coupon
UNION ALL SELECT 'coupon_issue', COUNT(*) FROM coupon_issue
UNION ALL SELECT 'coupon_issue_history', COUNT(*) FROM coupon_issue_history;

-- 상태 분포
SELECT status, COUNT(*) FROM coupon_issue GROUP BY status;

-- 재고 항등식이 깨진 쿠폰 (0행이어야 정상)
SELECT s.coupon_id, s.total_quantity, s.remaining_quantity, COUNT(i.coupon_issue_id) AS issued
FROM coupon_stock s
LEFT JOIN coupon_issue i ON i.coupon_id = s.coupon_id
GROUP BY s.coupon_id, s.total_quantity, s.remaining_quantity
HAVING s.total_quantity <> s.remaining_quantity + issued;
```

이 쿼리들은 눈으로 확인하는 수단이며, 정식 검증 도구가 아니다.

규칙 기반 검증은 `com.mocou.consistency`(B1 담당)가 맡는다. **판정식은 [정합성 검증 규칙 명세](./consistency-rules.md)에 확정돼 있고 구현은 예정 상태다.** 구현 전까지 정합성 확인은 위 쿼리와 명세의 판정식을 직접 실행하는 방법뿐이다.

```text
기본 설정에서는 시연용 쿠폰 ID가 `301`이다. Redis 초기화 결과는 다음 명령으로 확인한다.
```

```bash
docker compose exec redis redis-cli GET "coupon:{301}:stock"
docker compose exec redis redis-cli HGETALL "coupon:{301}:metadata"
```

stock은 기본값 10000, Metadata에는 openAtEpochSecond와 closeAtEpochSecond가 있어야 한다. round-count를 변경했다면 시연용 쿠폰 ID는 round-count + 1이다.

---

## 6. 알려진 제약

| 항목 | 내용 |
| --- | --- |
| 기준 시각 | `base-time` 미지정 시 실행 시각에 따라 데이터가 달라진다 (4.3 참고) |
| 시연 회차 | 발급 이력 0건이 **정상**이다. 검증에서 이상치로 취급하면 안 된다 |
| 중복 발급 | `UNIQUE (coupon_id, member_id)` 때문에 이 데이터에서는 발생 자체가 불가능하다 |
| 재고 불일치 | 역산으로 채우므로 적재 직후에는 정의상 성립한다 (3.6 참고) |
| 회차 상한 | `round-stock`이 `member-count`보다 크면 회차당 1인 1매를 지킬 수 없어 기동 시 예외로 막는다 |
