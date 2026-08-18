# B2 쿠폰 생명주기 정책

## 범위

이 문서는 쿠폰 사용, 상태 이력, 만료 처리와 만료 배치의 기준을 정한다.

관련 기능은 `F-STS-001`~`F-STS-004`이며, 원본 요구사항은 [구글 시트](https://docs.google.com/spreadsheets/d/1nB1hoAxqKC8wdWSc1fMKlG0xPMSquvXcf18okI1ejxw/edit?gid=241366860#gid=241366860)에서 관리한다.

## 상태와 전이

`미발급`은 `coupon_issue` 행이 없는 상태다. 저장 상태는 `ISSUED`, `USED`, `EXPIRED`만 사용하며 `USED`, `EXPIRED`는 최종 상태다.

```text
행 없음 ──발급──> ISSUED ──사용──> USED
                         └─만료──> EXPIRED
```

허용되지 않은 전이는 `INVALID_STATE_TRANSITION`으로 거부한다.

## 발급 이력 책임

A팀은 발급 트랜잭션에서 `coupon_issue` 생성과 `NULL → ISSUED` 최초 이력 기록을 함께 처리한다. 최초 이력의 멱등성 키는 `ISSUE:{couponIssueId}`를 사용한다.

B2는 발급 이후 `ISSUED → USED`, `ISSUED → EXPIRED` 전이와 이력을 담당한다.

> A팀과 최초 이력 책임을 확인한 뒤 이 문서의 전제를 확정한다.

## 시간과 만료

- `expires_at`은 발급 시 `issued_at + 14일`로 계산해 저장한다.
- B2는 `issued_at`을 다시 계산하지 않고 저장된 `expires_at`만 사용한다.
- DB 기준 시각이 `expires_at` 이상이면 만료다.
- 서버와 DB 타임존은 `Asia/Seoul`로 통일한다.
- 일반 조회는 상태를 변경하지 않는다.

사용 요청은 배치 실행 전이라도 만료된 쿠폰을 `COUPON_EXPIRED`로 거부한다. 실제 `ISSUED → EXPIRED` 전이와 이력 생성은 만료 배치가 수행한다.

사용과 만료 처리는 조건부 갱신으로 경쟁한다.

```text
사용: status = ISSUED AND expires_at > 현재 시각
만료: status = ISSUED AND expires_at <= cutoffAt
```

조건을 만족한 전이는 하나만 성공한다. 상태 변경과 이력 저장은 같은 트랜잭션에서 처리한다.

## 쿠폰 사용 API와 멱등성

```http
POST /api/v1/coupon-issues/{issueId}/use
Idempotency-Key: <클라이언트 생성 키>
```

- 멱등성 범위는 `(coupon_issue_id, idempotency_key)`다.
- 멱등성 키는 대소문자를 구분해 원문 그대로 비교한다.
- 같은 키와 같은 요청은 최초 성공 결과를 다시 반환한다.
- 같은 키를 다른 요청에 사용하면 `IDEMPOTENCY_CONFLICT`를 반환한다.
- 다른 키로 완료된 상태를 변경하려 하면 `INVALID_STATE_TRANSITION`을 반환한다.
- 실패한 전이는 상태 이력에 저장하지 않는다.

오류 코드:

| 상황 | HTTP | 코드 |
| --- | ---: | --- |
| 발급 쿠폰 없음 | 404 | `ISSUE_NOT_FOUND` |
| 멱등성 키 누락·형식 오류 | 400 | `INVALID_INPUT` |
| 같은 키를 다른 요청에 사용 | 409 | `IDEMPOTENCY_CONFLICT` |
| 허용되지 않은 전이 | 409 | `INVALID_STATE_TRANSITION` |
| 만료 쿠폰 사용 | 410 | `COUPON_EXPIRED` |

현재 `coupon_issue_history` 스키마를 그대로 사용한다. 1차 MVP에서는 신규 이력 컬럼을 추가하지 않는다.

## 만료 배치

- Spring Batch로 1분마다 실행하며, 주기와 청크 크기는 설정값으로 분리한다.
- 배치 시작 시 DB 시각을 `cutoffAt`으로 한 번 고정한다.
- 처리 대상은 `ISSUED`이고 `expires_at <= cutoffAt`인 쿠폰이다.
- 기본 청크 크기는 1,000건이다.
- 청크 실패 시 해당 청크의 상태와 이력을 함께 롤백한다.
- 실패한 Job은 동일한 `cutoffAt`으로 재시작한다.
- 이전 배치가 실행 중이면 새 실행은 건너뛴다.
- 1차 MVP에서는 배치 실행 인스턴스를 하나로 제한한다.

만료 이력의 멱등성 키는 `EXPIRE:{couponIssueId}:{expiresAt}`를 사용한다.

## B1 데이터 계약

- B1은 고정된 데이터 생성 기준 시각과 시드값으로 데이터를 만든다.
- `expires_at = issued_at + 14일` 규칙을 적용한다.
- `ISSUED`는 만료 전이고 `used_at`이 없다.
- `USED`는 `issued_at <= used_at < expires_at`을 만족한다.
- `EXPIRED`는 `used_at`이 없고 만료 시각이 지났다.
- 모든 발급 건은 `NULL → ISSUED` 이력을 가지며, 최종 상태 건은 후속 이력 한 건을 더 가진다.

## 시트 확인 항목

- `F-STS-001` → `FR-3.1`, `FR-3.6`
- `F-STS-002` → `FR-3.2`
- `F-STS-003` → `FR-2.6`, `FR-3.1`
- `F-STS-004` → `FR-2.6`

오류 코드표에는 `ISSUE_NOT_FOUND`, `INVALID_INPUT`, `IDEMPOTENCY_CONFLICT`를 추가하고, 상태 전이 관련 기능 ID를 `F-STS-*` 형식으로 맞춘다.

## 테스트 완료 기준

- 허용되지 않은 상태 전이, 상태 이력 누락, 중복 이력이 모두 0건이다.
- 같은 멱등성 키의 동시 요청은 상태 변경과 이력이 각각 1건이다.
- 사용과 만료 배치가 경합해도 최종 상태와 이력이 일치한다.
- 만료 배치 재시작 후 중복 이력이 없다.
- 통합 테스트는 Testcontainers MySQL을 사용한다.

300만 건 전체 검증은 PR CI가 아닌 시연 전 별도 실행으로 수행한다.

## 1차 MVP 제외 범위

- 관리자 수동 상태 변경 API
- 이력 발생 주체 컬럼
- 다중 서버에서의 만료 배치 실행
- 실패 응답까지 저장하는 별도 멱등성 요청 테이블
