# 쿠폰 만료 배치 구현·운영 가이드

이 문서는 만료 배치의 코드 흐름과 실행 설정을 설명한다. 만료 기준과 상태 전이 정책은 [쿠폰 생명주기 정책](./coupon-lifecycle-policy.md)을 따른다.

## 코드 흐름

```text
application.yml / 실행 옵션
        ├─ scheduler-enabled → ExpirationSchedulerProperties → ExpirationSchedulerState
        ├─ fixed-delay-ms → CouponExpirationScheduler
        └─ chunk-size → CouponExpirationBatchProperties → CouponExpirationTasklet

CouponExpirationScheduler
        ├─ ExpirationSchedulerState가 OFF면 자동 실행 요청을 건너뜀
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
| `ExpirationSchedulerProperties` | `scheduler-enabled` 설정값을 서버 시작 시 런타임 상태의 초기값으로 제공한다. |
| `ExpirationSchedulerState` | 단일 애플리케이션 인스턴스에서 API와 Scheduler가 공유하는 현재 자동 실행 상태를 관리한다. |
| `CouponExpirationScheduler` | 설정된 주기마다 현재 상태를 확인하고, ON일 때만 Job 실행을 요청한다. |
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
| `scheduler-enabled` | `true` | 서버 시작 시 자동 스케줄 상태의 초기값이다. 실행 중 상태 변경은 내부 API를 사용한다. |
| `fixed-delay-ms` | `60000` | 이전 Job 실행이 끝난 뒤 다음 자동 실행까지 기다리는 시간(ms)이다. |
| `chunk-size` | `2000` | 한 트랜잭션에서 조회·처리할 최대 만료 후보 수다. |

## 변경할 때 확인할 위치

| 바꾸려는 대상 | 수정 위치 |
| --- | --- |
| 서버 시작 시 자동 실행 기본값 | `application.yml`의 `scheduler-enabled` |
| 실행 중 자동 실행 켜기·끄기 | 내부 Scheduler Control API |
| 자동 실행 주기 | `application.yml`의 `fixed-delay-ms` |
| 청크 크기 | `application.yml`의 `chunk-size` |
| Job 실행·재시작 정책 | `CouponExpirationJobLauncher` |
| DB 기준 시각 조회 | `JdbcExpirationClock` |
| 만료 상태·이력 저장 SQL | `JdbcCouponExpirationRepository` |

## 테스트 위치

- `CouponExpirationSchedulerConditionTest`: Scheduler Bean 상시 등록과 설정값 기반 런타임 상태 초기화
- `CouponExpirationSchedulerTest`: Scheduler가 Job 실행을 요청하는지
- `ExpirationSchedulerControlControllerTest`: 자동 실행 상태 조회·변경과 입력 검증
- `CouponExpirationJobLauncherTest`: 중복 실행 방지, 실패 재시작, DB 시각 기반 새 Job 시작
- `CouponExpirationTaskletTest`: 청크 반복과 설정값 검증
- `CouponExpirationServiceTest`, `CouponExpirationIntegrationTest`: 상태 전이와 이력 정합성

## 런타임 Scheduler Control API

만료 스케줄러는 서버를 재시작하지 않고 내부 API로 자동 실행 상태를 변경할 수 있다. 모든 응답은 공통 `ApiResponse<T>` 형식을 사용하므로, 아래 응답 본문의 `enabled`는 `data` 안에 담긴다.

| API | 설명 |
| --- | --- |
| `GET /internal/lifecycle/expiration-scheduler` | 현재 자동 실행 상태를 조회한다. |
| `PUT /internal/lifecycle/expiration-scheduler` | 요청 본문의 `enabled` 값으로 자동 실행 상태를 변경한다. |

```json
{
  "enabled": false
}
```

`enabled`를 생략하거나 `null`로 보내면 `400 Bad Request`와 공통 `INVALID_INPUT` 응답을 반환한다.

OFF 요청은 이미 실행 중인 Job을 중단하지 않으며, 해당 Job을 기다리지 않고 즉시 상태를 변경한다. 이미 상태 확인을 통과한 스케줄 호출 1건은 Job 시작을 요청할 수 있지만, 그 이후 스케줄 주기의 자동 실행 요청은 건너뛴다. 서버를 재시작하면 런타임 상태는 `scheduler-enabled` 설정값으로 다시 초기화된다.

## 운영 범위

현재 상태는 애플리케이션 메모리에만 있으므로 **단일 애플리케이션 인스턴스**에서만 사용한다. Redis가 별도 서버나 컨테이너에 있어도 app 인스턴스가 하나라면 동작에 영향을 주지 않는다.

app 인스턴스를 여러 대로 늘릴 때는 Redis 등에 상태를 공유하고, 여러 Scheduler가 동시에 Job을 시작하지 않도록 분산 락을 추가해야 한다. 이 경우 Redis 상태는 재시작 뒤에도 남으므로, 현재의 "재시작 시 `scheduler-enabled`로 복원" 정책도 함께 재설계한다.
