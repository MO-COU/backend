# 선착순 쿠폰 발급 시스템 MOCOU - Backend

대규모 트래픽 환경에서 초과 발급 0건 · 1인 1매 · 정합성을 보장하는 선착순 쿠폰 발급 시스템.

<details>
<summary>기술 스택</summary>

| 구분 | 선택 |
|---|---|
| 언어 | Java 21 (LTS) |
| 프레임워크 | Spring Boot 4.1.0-RC1 |
| 빌드 | Gradle (Kotlin DSL) 9.0 |
| DB | MySQL 8.0 |
| ORM | Spring Data JPA + JdbcTemplate |
| 캐시/동시성 | Redis 8.8 (Lettuce) |
| 배치 | Spring Batch 6.0.3 |
| 마이그레이션 | Flyway (`spring-boot-starter-flyway`) |
| 인프라 | Docker Compose |
| 테스트 | JUnit 5, Testcontainers, AssertJ |
| 부하테스트 | k6 |
| CI | GitHub Actions |

</details>

<details>
<summary>팀 구성</summary>

| 팀 | 담당 영역 |
|---|---|
| A | 발급·트래픽 제어 (Redis 재고, 1인 1매, 대기열, 발급 실패 처리) |
| B | 쿠폰 생명주기·정합성 (상태 전이, 만료 배치, 정합성 검증) |
| C | 관리자·관측·테스트 (대시보드, Mock 알림, 부하 테스트, CI/CD) |

패키지 소유권 규칙은 [CONTRIBUTING.md](./CONTRIBUTING.md)를 참고.

</details>

## 시작하기

### 1. 사전 준비
- JDK 21
- Docker / Docker Compose

### 2. 로컬 인프라 기동
```
docker compose up -d
```
MySQL(3306), Redis(6379) 기동
Flyway가 스키마에 `src/main/resources/db/migration` 자동 적용

> **A/B/C팀 공통** 대시보드·부하테스트처럼 DB/Redis를 직접 다루지 않는 작업이라도, 로컬에서 앱을 띄우려면 실제 MySQL/Redis가 있어야 함 — [CONTRIBUTING.md](./CONTRIBUTING.md) 참고).

### 3. 애플리케이션 실행
```
./gradlew bootRun
```

## 프로젝트 구조

```
src/main/java/com/mocou/
├── global/          # 공통 (config, exception, response, masking)
├── member/          # 회원 도메인 (공통)
├── coupon/          # 쿠폰/재고 마스터 도메인 (공통)
├── issue/           # 발급·트래픽 제어 (A팀)
├── lifecycle/       # 쿠폰 생명주기 (B팀)
├── consistency/     # 정합성 검증 (B팀)
├── admin/           # 관리자 대시보드 (C팀)
├── notification/    # Mock 알림 (C팀)
└── datagen/         # 더미데이터 생성 배치 (공통)
```

## DB 스키마 Flyway

10개 테이블 (회원/쿠폰/발급/이력/알림/실패로그/검증 3종)
전체 DDL : [`src/main/resources/db/migration/V1__mocou_schema.sql`](src/main/resources/db/migration/V1__mocou_schema.sql)

## 테스트 실행
```
./gradlew test
```

통합 테스트는 Testcontainers로 실제 MySQL/Redis 컨테이너를 띄워서 검증합니다 (DB/Redis Mock 금지).
**예외 — 알림(`notification`)**: 알림 로직은 Testcontainers 통합 테스트 대상이 아니라, Mock 발송 결과가 로그에 잘 기록되는지만 검증.

## 기여 방법

[CONTRIBUTING.md](./CONTRIBUTING.md) 참고.
