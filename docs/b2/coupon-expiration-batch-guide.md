# 쿠폰 만료 배치 구현·운영 가이드

이 문서는 만료 배치의 코드 흐름과 실행 설정을 설명한다. 만료 기준과 상태 전이 정책은 [쿠폰 생명주기 정책](./coupon-lifecycle-policy.md)을 따른다.

## 코드 흐름

```text
application.yml / 실행 옵션
        ├─ scheduler-enabled, fixed-delay-ms → CouponExpirationScheduler
        └─ chunk-size → CouponExpirationBatchProperties → CouponExpirationTasklet

CouponExpirationScheduler
        ↓
CouponExpirationJobLauncher
        ├─ 실행 중인 Job이면 건너뜀
        ├─ 마지막 Job이 실패면 재시작
        └─ ExpirationClock의 DB 시각으로 새 Job 시작
                ↓
couponExpirationJob → CouponExpirationTasklet
                ↓
CouponExpirationService → JdbcCouponExpirationRepository
```

`cutoffAt`은 Job을 시작할 때 DB 시각으로 한 번 고정한다. 따라서 한 Job 안에서 여러 청크를 처리해도 만료 기준 시각이 바뀌지 않는다.

## 클래스별 책임

| 클래스 | 책임 |
| --- | --- |
| `CouponExpirationBatchProperties` | 청크 크기 설정값을 Tasklet에 전달한다. |
| `CouponExpirationScheduler` | 설정된 주기마다 Job 실행을 요청한다. |
| `CouponExpirationJobLauncher` | 중복 실행 방지, 실패 Job 재시작, 새 Job 시작 정책을 처리한다. |
| `ExpirationClock` / `JdbcExpirationClock` | 앱 서버 시간이 아닌 DB 시각을 만료 기준으로 제공한다. |
| `CouponExpirationTasklet` | 고정된 `cutoffAt`과 청크 크기로 만료 처리를 반복한다. |
| `CouponExpirationService` | 조건부 EXPIRED 갱신에 성공한 건만 만료 이력을 저장한다. |
| `JdbcCouponExpirationRepository` | 만료 후보 조회, 상태 변경, 이력 저장 SQL을 실행한다. |

## 실행 설정

일반적인 설정 변경은 `src/main/resources/application.yml` 또는 실행 옵션에서 한다.

```yaml
mocou:
  lifecycle:
    expiration:
      scheduler-enabled: true
      fixed-delay-ms: 60000
      chunk-size: 2000
```

| 설정 | 기본값 | 변경 효과 |
| --- | ---: | --- |
| `scheduler-enabled` | `true` | `true`면 자동 스케줄을 실행하고, `false`면 자동 스케줄을 중지한다. |
| `fixed-delay-ms` | `60000` | 이전 Job 실행이 끝난 뒤 다음 자동 실행까지 기다리는 시간(ms)이다. |
| `chunk-size` | `2000` | 한 트랜잭션에서 조회·처리할 최대 만료 후보 수다. |

## 변경할 때 확인할 위치

| 바꾸려는 대상 | 수정 위치 |
| --- | --- |
| 자동 실행 켜기·끄기 | `application.yml`의 `scheduler-enabled` |
| 자동 실행 주기 | `application.yml`의 `fixed-delay-ms` |
| 청크 크기 | `application.yml`의 `chunk-size` |
| Job 실행·재시작 정책 | `CouponExpirationJobLauncher` |
| DB 기준 시각 조회 | `JdbcExpirationClock` |
| 만료 상태·이력 저장 SQL | `JdbcCouponExpirationRepository` |

## 테스트 위치

- `CouponExpirationSchedulerConditionTest`: 자동 스케줄 설정에 따른 Scheduler Bean 등록 여부
- `CouponExpirationSchedulerTest`: Scheduler가 Job 실행을 요청하는지
- `CouponExpirationJobLauncherTest`: 중복 실행 방지, 실패 재시작, DB 시각 기반 새 Job 시작
- `CouponExpirationTaskletTest`: 청크 반복과 설정값 검증
- `CouponExpirationServiceTest`, `CouponExpirationIntegrationTest`: 상태 전이와 이력 정합성
