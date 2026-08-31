# 🎟️ MOCOU - 선착순 쿠폰 발급 시스템

> **대규모 동시 요청 환경에서 초과 발급을 방지하고, 1인 1매와 Redis-DB 간 데이터 정합성을 보장하는 선착순 쿠폰 발급 시스템**

단순한 쿠폰 CRUD 구현을 넘어, **동시성 제어 · 비동기 처리 · 장애 복구 · 데이터 정합성 · 대규모 부하 검증**을 주요 목표로 개발한 프로젝트입니다.

## 🛠 Tech Stack

### Backend
![Java](https://img.shields.io/badge/Java%2021-007396?style=flat&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot%204.1.0-6DB33F?style=flat&logo=springboot&logoColor=white)
![Spring Batch](https://img.shields.io/badge/Spring%20Batch-6DB33F?style=flat&logo=spring&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle%209-02303A?style=flat&logo=gradle&logoColor=white)

### Database & Messaging
![MySQL](https://img.shields.io/badge/MySQL%208.0-4479A1?style=flat&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis%208.8-DC382D?style=flat&logo=redis&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat&logo=flyway&logoColor=white)

### Infrastructure · Test · CI
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white)
![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?style=flat&logo=amazonec2&logoColor=white)
![AWS S3](https://img.shields.io/badge/AWS%20S3-569A31?style=flat&logo=amazonwebservices&logoColor=white)
![AWS SSM](https://img.shields.io/badge/AWS%20SSM-232F3E?style=flat&logo=amazonwebservices&logoColor=white)
![JUnit5](https://img.shields.io/badge/JUnit%205-25A162?style=flat&logo=junit5&logoColor=white)
![Testcontainers](https://img.shields.io/badge/Testcontainers-2496ED?style=flat&logo=testcontainers&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=flat&logo=k6&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=flat&logo=githubactions&logoColor=white)

## 📌 프로젝트 개요

선착순 쿠폰 발급은 짧은 시간 안에 다수의 사용자가 동시에 요청하므로 초과 발급, 중복 발급, DB 경합, Redis-DB 불일치, 이벤트 유실과 알림 실패가 발생할 수 있습니다.

MOCOU는 **Redis를 발급 처리의 진입점으로 사용하고 Redis Stream으로 DB 저장을 비동기화**합니다. API 성공 건수뿐 아니라 Redis와 MySQL을 교차 검증하고, 부하 테스트 결과까지 기록할 수 있도록 구성했습니다.

## 🎯 핵심 목표

| 목표 | 내용 |
| --- | --- |
| 초과 발급 방지 | 쿠폰 재고 이상의 발급이 발생하지 않도록 보장 |
| 1인 1매 | 하나의 쿠폰에 대해 동일 회원의 중복 발급 방지 |
| 동시성 제어 | Redis Lua Script로 발급 판단을 원자적으로 수행 |
| 빠른 응답 | Redis 예약 후 `202 Accepted` 응답, DB 저장은 비동기 처리 |
| 장애 복구 | Redis Stream Consumer Group, PEL, 재시도 및 DLQ 구성 |
| 데이터 정합성 | Redis ↔ MySQL 및 상태·이력 간 정합성 검증 |
| 대규모 검증 | k6를 이용한 Ramp-up, Spike, 고정 요청률 부하 테스트 |
| 운영 안정성 | 오류 로그 파일화, S3 백업, Redis AOF 영속화 |

## 🏗 System Architecture

<!-- 시스템 아키텍처 이미지는 이 위치에 추가하세요. -->
<!-- 예: <img src="./docs/images/system-architecture.png" width="100%" alt="MOCOU 시스템 아키텍처"> -->

시스템의 전체 구성과 발급 처리·장애 복구·운영 아키텍처는 [아키텍처 문서](./docs/architecture.md)에서 확인할 수 있습니다.

## 📊 Load Test

k6로 점진 증가(Ramp), 순간 동시 요청(20,000 VU), 실제 사용자 유입과 유사한 Ramp Once, 고정 RPS, 제한적 반복 요청을 재현했습니다. 기본 쿠폰 재고는 **10,000장**입니다.

### 주요 검증 기준

```text
발급 수 ≤ 총 재고
회원별 발급 수 ≤ 1
DB 발급 수 + DB 잔여 재고 = 총 재고
Redis 발급 회원 = DB 발급 회원
Redis Stream 미처리 Event = 0
Consumer Pending = 0
```

<!-- 최종 부하 테스트 결과 이미지 또는 표는 이 위치에 추가하세요. -->

## 🧪 Test Strategy

JUnit 5와 Testcontainers를 이용해 실제 MySQL·Redis 환경에서 Redis Lua 동시성, Stream Event 생성, Consumer DB 적재, PEL·DLQ 복구, 정합성, 쿠폰 상태 전이, Spring Batch, Transactional Outbox, 관리자 API를 검증합니다.

```bash
./gradlew test
```

## 👥 Team

| 이름 | 주요 담당 |
| --- | --- |
| 권도하 | 공통 기능 및 배포·운영 환경 |
| 권혁준 | 쿠폰 생명주기, 만료 Batch, Redis 발급 현황, 운영 로그 및 S3 백업 |
| 박서희 | 관리자 API, k6 부하 테스트 및 AWS SSM 자동 실행 |
| 장근창 | 대용량 데이터 생성 및 Redis·DB 정합성 검증 |
| 조현빈 | Redis Stream Consumer, 재처리·DLQ, Transactional Outbox |
| 지창민 | Redis Lua 발급, 재고·중복 제어, 발급 순번 및 Redis 상태 관리 |

## 📁 Project Structure

```text
src/main/java/com/mocou/
├── global/          # 공통 설정, 예외, 응답, Logging
├── member/          # 회원
├── coupon/          # 쿠폰 및 재고
├── issue/           # Redis 발급 / Stream / Consumer
├── lifecycle/       # 쿠폰 사용 / 만료 / Batch
├── consistency/     # 데이터 정합성 검증
├── admin/           # 관리자 API
├── notification/    # Transactional Outbox / 알림
└── datagen/         # 대용량 테스트 데이터 생성
```

## 🏃 Run Locally

### Requirements

* JDK 21
* Docker
* Docker Compose

```bash
docker compose up -d
./gradlew bootRun
./gradlew test
```

## 📚 Documents

* [아키텍처](./docs/architecture.md) - 시스템 구성, 발급 흐름, 장애 복구 및 운영 구조
* [정합성 검증 규칙 명세](./docs/b1/consistency-rules.md)
* [쿠폰 생명주기 정책](./docs/b2/coupon-lifecycle-policy.md)
* [쿠폰 만료 배치 가이드](./docs/b2/coupon-expiration-batch-guide.md)
* [k6 부하 테스트 실행 및 시나리오](./load-test/README.md)
* [시스템 오류 파일 로그 운영](./docs/operations/system-error-file-logging.md)

---

## MOCOU

**선착순이라는 하나의 문제를 동시성 제어에서 끝내지 않고 비동기 처리, 데이터 정합성, 장애 복구, 부하 테스트와 운영까지 확장하여 검증한 프로젝트입니다.**
