# 쿠폰 발급 전체 시나리오 검증

## 문서 정보

| 상태 | 담당 | 검토 | 대상 환경 | 최종 수정일 |
| --- | --- | --- | --- | --- |
| 검증 대기 | 박서희 | C팀 및 연동 담당자 | 로컬 → EC2 | 2026-08-24 |

## 목적

k6 발급 요청 이후 Redis 예약 결과가 DB에 정확히 반영됐는지 확인한다.
최종적으로 Redis 재고, DB 발급 건수와 B팀 정합성 검증 결과가 모두 일치해야 한다.

## 현재 실행 가능한 범위

- k6 스크립트 구문 및 요청 규격 확인
- DB 발급 건수, 잔여 재고, 중복 발급 확인
- 최초 발급 이력과 상태 시각 확인

Redis Stream Consumer와 정합성 검증 API는 `dev`에 통합됐다. 테스트 데이터를 초기화한 환경에서 실제 실행 결과만 기록하면 된다.

## 선행 작업과 대기 조건

| 단계 | 필요한 작업 | 준비 상태 확인 기준 |
| --- | --- | --- |
| 발급 요청 | A팀 Redis 발급 API | 배포 환경에서 `POST /api/coupons/{couponId}/issues`가 `202` 또는 업무상 거절 응답을 반환함 |
| DB 반영 | Redis Stream Consumer | 발급 예약 후 `coupon_issue`와 최초 `coupon_issue_history`가 생성됨 |
| 관리자 조회 | C팀 관리자 API | DB 반영 후 재고·발급 이력 조회 결과가 변경됨 |
| 정합성 판정 | B팀 정합성 검증 | 검증 실행 식별자와 최종 PASS/FAIL을 조회할 수 있음 |

선행 작업이 준비되지 않은 단계는 실패로 기록하지 않고 `BLOCKED`와 대기 사유를 남긴다.

## 검증 범위

### 포함

- 발급 API 응답과 k6 결과 집계
- Redis 재고 차감 및 Stream 이벤트 생성
- Consumer의 DB 발급 이력 적재
- DB 재고·발급 이력·중복 여부 검증
- B팀 정합성 검증 결과 확인

### 현재 제외

- Redis 또는 DB 서버 강제 종료와 복구
- 장시간 안정성(soak) 테스트
- 여러 애플리케이션 인스턴스 간 성능 비교

제외 항목은 1차 전체 흐름이 통과한 뒤 별도 장애 시나리오로 다룬다.

## 실행 환경 기록

결과를 비교할 수 있도록 실행 전에 아래 값을 기록한다.

| 항목 | 기록 값 |
| --- | --- |
| Git commit |  |
| 실행 위치 | 로컬 / EC2 |
| 애플리케이션 인스턴스 사양·개수 |  |
| MySQL·Redis 버전 |  |
| 쿠폰 ID·최초 재고 |  |
| Consumer 방식·설정 |  |
| 테스트 스크립트와 MODE |  |
| VU·Ramp-up·총 요청 수 |  |

## 실행 전 확인

- 시연용 쿠폰과 재고가 DB에 생성되어 있어야 한다.
- Redis 재고와 발급 시간이 초기화되어 있어야 한다.
- 발급 API와 Redis Stream Consumer가 실행 중이어야 한다.
- 테스트마다 새 쿠폰을 사용하거나 DB와 Redis를 초기 상태로 복구해야 한다.

## 실행 순서

1. 소규모 스모크 테스트를 실행한다.
2. Redis Stream Consumer가 DB 적재를 끝낼 때까지 기다린다.
3. DB 사전 점검 SQL을 실행한다.
4. Redis 잔여 재고와 DB 결과를 대조한다.
5. B팀 정합성 검증을 실행하고 결과를 기록한다.
6. 소규모 검증이 통과하면 요청량을 단계적으로 올린다.

현재는 애플리케이션 연결만 확인하는 스모크 테스트를 실행할 수 있다.

```bash
MODE=smoke VERIFY_DB=false VERIFY_REDIS=false \
  ./load-test/run-full-flow.sh
```

Consumer와 테스트 데이터까지 연결된 뒤에는 DB와 Redis 검증을 함께 실행한다.

```bash
MODE=smoke COUPON_ID=301 VERIFY_DB=true VERIFY_REDIS=true \
  ./load-test/run-full-flow.sh
```

스모크 테스트가 통과하면 `MODE=duplicate`, 마지막으로 `MODE=rush` 순서로 진행한다.
각 테스트는 발급 데이터를 변경하므로 테스트마다 새로운 쿠폰을 사용하거나 DB와 Redis를 초기화한다.

테스트 단계는 아래 순서를 지킨다. 앞 단계가 실패하면 다음 단계로 넘어가지 않는다.

| 단계 | 목적 | 다음 단계 진입 조건 |
| --- | --- | --- |
| smoke | 연결과 응답 규격 확인 | 요청·응답 및 서버 로그 정상 |
| duplicate | 동일 회원 중복 방어 확인 | DB 중복 0건 |
| rush | 20,000명 최종 부하와 정합성 확인 | 아래 완료 기준 전부 충족 |

`rush`는 `ramping-vus`로 60초 동안 중복 없는 사용자를 0명에서 20,000명까지 늘린다. 각 사용자는 발급 API를 한 번만 호출한다.

최종 완판 테스트는 테스트 시작 전 DB 건수를 기준으로 신규 10,000건이 반영될 때까지 기다리도록 기대값을 지정한다.

```bash
MODE=rush COUPON_ID=301 VUS=20000 RAMP_UP=60s EXPECTED_STOCK=10000 \
  VERIFY_DB=true VERIFY_REDIS=true VERIFY_CONSISTENCY=true \
  EXPECTED_NEW_DB_COUNT=10000 \
  ./load-test/run-full-flow.sh
```

## 결과 기록

| 실행 시각 | 환경 | 쿠폰 ID | 요청 수 | 성공 | 품절 | 중복 | 5xx | p95 | p99 | DB 발급 수 | Redis 재고 | 정합성 | 비고 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
|  | 로컬/EC2 |  |  |  |  |  |  |  |  |  |  | PASS/FAIL |  |

다음 증적을 실행 시각과 Git commit이 드러나도록 함께 보관한다.

- k6 종료 요약과 가능하면 원본 결과 파일
- 애플리케이션 오류 로그 및 대표 `traceId`
- Redis `XLEN`, `XINFO GROUPS`, `XPENDING` 확인 결과
- `verify-issue-result.sql` 실행 결과
- B팀 정합성 검증의 DB `runId`, 최종 판정, 위반 건수

권장 파일명은 `YYYYMMDD-HHmm_<commit>_<mode>_<couponId>` 형식이다.

## 합격 기준과 중단 조건

기능·정합성 기준은 아래 완료 기준을 적용한다. 현재 `rush-issue.js`의 임시 성능 기준은 p95 2초 미만이다. p99와 TPS는 우선 측정값으로 기록하고, 최종 합격 기준은 테스트 전에 팀이 합의한 뒤 고정한다. 결과를 본 다음 합격선을 바꾸지 않는다.

다음 상황에서는 테스트를 중단하고 원인을 먼저 확인한다.

- 5xx 또는 타임아웃이 합의된 허용치를 초과한다.
- Redis 재고가 음수가 된다.
- Consumer 적체가 계속 증가하거나 처리 완료를 확인할 수 없다.
- DB 중복 또는 최초 발급 이력 누락이 발견된다.
- 테스트 환경의 CPU·메모리 부족으로 결과 신뢰성이 떨어진다.

## 완료 기준

- 발급 성공 건수와 DB 발급 건수가 일치한다.
- DB 잔여 재고와 Redis 잔여 재고가 일치한다.
- 동일 회원 중복 발급이 0건이다.
- 최초 발급 이력 누락이 0건이다.
- 정합성 검증 결과가 PASS이고 위반 건수가 0이다.
- 다른 팀원이 이 문서만 보고 같은 순서로 실행할 수 있다.

## 재고·발급 건수 판정 기준

수치를 직접 계산하지 않아도 된다. 실행 스크립트가 테스트 전 DB 발급 건수를 기록하고, 아래 값을 Redis와 DB에서 직접 조회한다.

```text
계산된 잔여 재고 = DB 최초 재고 - DB 발급 건수
계산된 잔여 재고 = DB 잔여 재고
계산된 잔여 재고 = Redis 잔여 재고
```

예를 들어 최초 재고가 10,000장이고 DB에 발급이 8,000건 저장됐다면 DB와 Redis의 잔여 재고는 모두 2,000이어야 한다.
세 비교 중 하나라도 다르면 스크립트가 `FAIL`을 출력하고 실패 상태로 종료한다.

`verify-issue-result.sql`은 k6 직후 빠르게 확인하는 쿠폰 단위 사전 점검이다. 공식 최종 정합성 판정은 [B1 정합성 검증 규칙 명세](../b1/consistency-rules.md)의 R1~R7 실행 결과를 사용한다.

실행 결과 파일명에 쓰는 `TEST_LABEL`은 사람이 결과를 구분하기 위한 문자열이다. `coupon_issue_run.run_id`나 `verification_run.run_id`와 같은 DB 숫자 식별자가 아니다.

## 재실행 및 결과 해석

- 코드·설정·환경이 같아도 단일 실행값만으로 결론을 내리지 않는다.
- 최종 20,000명 테스트를 여러 번 돌리기 어렵다면, 소규모 기준 테스트를 반복해 변동성을 먼저 확인하고 최종 테스트 횟수와 제약을 결과에 명시한다.
- 비정상적으로 튄 값은 삭제하지 않고 로그, 시스템 자원, Stream 적체와 함께 원인을 기록한다.
- 실패 후 재실행할 때는 코드 변경 여부와 DB·Redis 초기화 여부를 반드시 남긴다.

## 공식 참고 자료

- [Grafana k6 테스트 생명주기](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/): setup, VU 실행, teardown 단계 구성
- [Grafana k6 Thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/): 성능 합격·실패 기준 자동화
- [Grafana k6 기본 메트릭](https://grafana.com/docs/k6/latest/using-k6/metrics/): TPS, 실패율, 응답시간 해석
- [Redis Streams 공식 문서](https://redis.io/docs/latest/develop/data-types/streams/): `XLEN`, `XINFO`, `XPENDING`을 이용한 Stream 관측
- [Spring Batch 테스트 공식 문서](https://docs.spring.io/spring-batch/reference/5.2/testing.html): Job·Step 단위 및 종단 간 테스트
