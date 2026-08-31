# MOCOU 시스템 아키텍처

MOCOU는 Redis를 발급 처리의 진입점으로 사용해 선착순 발급의 핵심 판단을 원자적으로 처리하고, Redis Stream을 통해 MySQL 저장을 비동기화합니다.

## 전체 발급 흐름

```text
사용자 발급 요청 → Spring Boot API → Redis Lua Script
                                      ├─ 발급 가능 시간 검증
                                      ├─ 회원 중복 발급 검증
                                      ├─ Redis 재고 차감
                                      ├─ 예약 순번 생성
                                      └─ Redis Stream 이벤트 생성
                                               ├─ 202 Accepted 응답
                                               ▼
                                        Redis Stream → Consumer Group
                                                   ├─ 정상 처리 → MySQL Batch INSERT
                                                   └─ 실패 → PEL → 재시도 → DLQ → 장기 재처리
```

발급 요청의 핵심 판단은 MySQL이 아닌 **Redis Lua Script 하나에서 원자적으로 수행**합니다. DB 저장은 사용자 응답 경로에서 분리하여 Redis Stream Consumer가 비동기로 처리합니다.

## Redis Lua 기반 원자적 쿠폰 발급

Lua Script는 발급 가능 시간 확인, 중복 회원 확인, 재고 확인·차감, 발급 회원 등록, 예약 순번 생성, Stream Event 생성을 한 번에 수행합니다. 하나의 명령처럼 원자적으로 실행되므로 별도의 분산 락 없이 동시 요청 간 재고 정합성을 유지합니다.

### 주요 Redis Key

| Key | 자료구조 | 용도 |
| --- | --- | --- |
| `coupon:{id}:stock` | String | 쿠폰 잔여 재고 |
| `coupon:{id}:metadata` | Hash | 쿠폰 오픈·마감 시각 |
| `coupon:{id}:issued-members` | Sorted Set | 발급 회원 및 예약 순번 |
| `coupon:{id}:issue-sequence` | String | 전역 예약 순번 |
| `coupon:{id}:issue-stream` | Stream | DB 저장용 발급 이벤트 |
| `coupon:{id}:issue-result-counts` | Hash | 발급 결과별 실시간 집계 |

## Redis Stream 기반 비동기 DB 저장

Redis 예약 성공 이벤트를 Consumer가 일정 Chunk Size와 Batch Window 단위로 모아 JDBC Batch INSERT로 저장합니다. 따라서 DB 왕복 횟수를 줄이고, 사용자에게 반환하는 `202 Accepted`는 **Redis 예약 완료**를 의미하며 MySQL 저장 완료를 의미하지 않습니다.

## PEL · Retry · DLQ 장애 복구

Consumer가 읽은 메시지를 처리하다 실패하면 해당 메시지는 Pending Entries List(PEL)에 남습니다. MOCOU는 이를 다시 가져와 재시도하고, 재시도 한도를 초과한 이벤트는 DLQ로 분리해 장기 재처리와 최종 실패 기록을 수행합니다. 정상 이벤트 처리와 장애 이벤트 복구가 서로 영향을 최소화하도록 메인 Stream에서 무한 재시도하지 않습니다.

## Redis 발급 순번 관리

발급 성공 여부를 판단하는 Lua Script에서 `INCR`을 사용해 쿠폰별 전역 예약 순번을 생성합니다. 발급 회원은 Sorted Set에 `memberId`를 member, `issueSequence`를 score로 저장하여 중복 발급 검사와 순번 관리를 함께 처리합니다. 애플리케이션 내부 Counter와 달리 여러 Spring 인스턴스로 확장해도 같은 순번이 생성되지 않습니다.

## Redis ↔ DB 정합성 검증

비동기 구조에서는 Redis 예약 성공과 MySQL 저장 사이에 일시적 차이가 있으므로 별도 정합성 검증 기능을 제공합니다.

* 동일 쿠폰의 회원 중복 발급 및 총 재고 대비 초과 발급 여부
* DB 재고와 발급 건수 관계, 쿠폰 상태와 상태 변경 시각, 이력 연결 관계
* FK 참조 무결성
* Redis 발급 회원·재고·예약 순번과 DB 데이터 비교

검증 실행 시 DB 데이터를 하나의 일관된 Snapshot에서 읽고, 테스트에서는 의도적으로 잘못된 데이터를 주입해 각 규칙의 오류 탐지 여부도 검증합니다.

## Transactional Outbox 기반 알림 처리

쿠폰 발급 또는 사용의 비즈니스 데이터와 알림 데이터를 동일 DB Transaction에서 `PENDING` 상태로 저장합니다. Commit 후 Notification Dispatcher가 알림을 처리하며, 성공 시 `SENT`, 실패 시 재시도 후 `FAILED`로 전이합니다. 이 구조는 DB Commit과 알림 생성 사이의 장애로 인한 이벤트 유실을 방지하고, 외부 알림 실패가 쿠폰 발급 결과에 영향을 주지 않게 합니다.

## 쿠폰 생명주기 관리

```text
ISSUED ──▶ USED
   └─────▶ EXPIRED
```

사용 요청에는 조건부 상태 변경을 적용해 이미 사용되거나 만료된 쿠폰이 다시 사용되는 것을 방지합니다. 만료 대상은 Spring Batch로 처리하고, 상태 변경과 이력을 함께 기록합니다. 운영·부하 테스트 중에는 스케줄러 실행 여부를 서버 재시작 없이 제어할 수 있습니다.

## 부하 테스트 자동화

```text
관리자 → Spring Boot → AWS SSM Run Command → k6 EC2 → k6 Test → MOCOU_RESULT → coupon_issue_run
```

관리자는 API로 부하 테스트를 실행하고 `runId`로 실행 상태와 결과를 조회합니다. 실행 조건과 결과를 DB에 기록해 서로 다른 부하 테스트를 비교할 수 있습니다.

## 관측과 운영

### Redis 실시간 발급 현황

Redis Hash Counter로 `RESERVED`, `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `COMPENSATED`를 집계합니다. 관리자 API는 Redis 예약 수와 DB 적재 수를 함께 반환해 비동기 적재 진행 상태를 보여 줍니다.

### Redis AOF

Redis에는 재고뿐 아니라 아직 DB에 반영되지 않은 Stream Event도 저장됩니다. 운영 환경에서는 다음 AOF 설정과 Docker Volume을 사용하여 Redis 컨테이너 재생성 이후에도 데이터를 복구할 수 있게 합니다.

```text
appendonly yes
appendfsync everysec
```

### ERROR Log S3 Backup

```text
Spring Boot → system-error.log → 날짜/10MB Rolling → .gz Archive → EC2 Cron → AWS S3
```

ERROR 이상 로그는 날짜 또는 10MB 기준으로 롤링하고 Docker Named Volume에 보관합니다. 매일 S3에 업로드하며, 실패하면 다음 실행에서 재시도하고, 업로드 확인 후 로컬 로그를 삭제합니다. S3 Lifecycle은 90일 보관을 적용합니다.

## 인프라 구성

```text
Application EC2: Spring Boot, MySQL 8.0, Redis 8.8 (Docker Compose)
Load Test EC2: k6
AWS S3: ERROR Log Archive
GitHub: GitHub Actions
```

운영 애플리케이션은 Docker Compose로 구성하며, 부하 테스트는 애플리케이션 서버 자원을 소비하지 않도록 별도 k6 EC2에서 수행합니다.

## 관련 문서

* [정합성 검증 규칙 명세](./b1/consistency-rules.md)
* [쿠폰 생명주기 정책](./b2/coupon-lifecycle-policy.md)
* [쿠폰 만료 배치 가이드](./b2/coupon-expiration-batch-guide.md)
* [k6 부하 테스트 실행 및 시나리오](../load-test/README.md)
* [시스템 오류 파일 로그 운영](./operations/system-error-file-logging.md)
