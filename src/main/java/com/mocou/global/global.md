# global 패키지

전체 팀이 공통으로 쓰는 코드가 모인 패키지입니다. 특정 팀 소유가 아니라 **읽기 전용으로 누구나 참조 가능**하고, 여기 있는 클래스를 임의로 복제해서 각자 만들지 말고 이걸 재사용해주세요. 개인정보 마스킹은 별도로 [masking/masking.md](./masking/masking.md)에 정리했습니다.

## 하위 패키지 구성

```
global/
├── response/    ApiResponse, ErrorResponse       — 모든 API의 공통 응답 형식
├── exception/   ErrorCode, BusinessException,
│                GlobalExceptionHandler           — 실패 코드 정의 + 예외 → 응답 자동 변환
├── logging/     TraceIdFilter                    — 요청별 추적 ID 부여
└── masking/     MaskingUtils                     — 개인정보 마스킹
```

## 요청 하나가 흘러가는 흐름

```
요청 도착
  → TraceIdFilter가 추적 ID를 발급해서 MDC에 저장 (가장 먼저 실행됨)
  → Controller → Service 호출
      ├─ 정상 처리: ApiResponse.success(data) 반환
      └─ 실패 처리: BusinessException(ErrorCode.XXX) 던짐
  → (예외가 던져졌다면) GlobalExceptionHandler가 잡아서 ApiResponse.error(...)로 변환
  → 응답에 MDC의 추적 ID가 자동으로 실려서 나감
```

## `response` — 공통 응답 봉투

**`ApiResponse<T>`**: 모든 API가 이 형식으로 응답합니다.
```json
{ "success": true, "data": { ... }, "error": null, "traceId": "...", "timestamp": "..." }
```
컨트롤러에서 직접 생성자를 쓰지 말고 정적 팩토리 메서드만 씁니다.
```java
return ApiResponse.success(dto);   // 데이터 있는 성공
return ApiResponse.success();      // 데이터 없는 성공 (상태 변경 API 등)
```
`error(...)` 메서드는 직접 호출할 일이 거의 없습니다 — `GlobalExceptionHandler`가 대신 호출해줍니다.

**`ErrorResponse`**: `ApiResponse.error`의 `error` 필드에 들어가는 `{ code, message }` 쌍입니다. `code`는 `ErrorCode` enum 이름 그대로 내려갑니다.

## `exception` — 실패 코드 정의 + 자동 변환

**`ErrorCode`**: 실패 케이스를 코드로 모아둔 enum입니다. **새로운 실패 상황이 생기면 각자 문자열을 만들지 말고 여기에 항목을 추가**해주세요 (HTTP 상태코드 + 기본 메시지를 같이 들고 있습니다).

현재 정의된 코드: `INVALID_INPUT`, `METHOD_NOT_ALLOWED`, `SOLD_OUT`, `DUPLICATE`, `NOT_MEMBER`, `NOT_OPEN_YET`, `SYSTEM_ERROR`

팀원들이 더 추가할 예정 : ex)`COUPON_NOT_FOUND`, `SERVICE_UNAVAILABLE`, `INVALID_STATE_TRANSITION`/`ISSUE_NOT_FOUND` `COUPON_EXPIRED`

**`BusinessException`**: "버그가 아니라 예상된 실패"임을 표현하는 예외입니다. 서비스 로직에서 이렇게 던지면 됩니다.
```java
if (coupon == null) {
    throw new BusinessException(ErrorCode.NOT_MEMBER);
}
```
컨트롤러에서 try-catch로 잡을 필요가 없습니다 — 아래 핸들러가 대신 처리합니다.

**`GlobalExceptionHandler`**: `@RestControllerAdvice`로, 예외를 가로채 `ApiResponse` 형식으로 통일해서 응답합니다. 처리하는 예외 4가지:

| 예외 | 상황 | 응답 코드 |
|---|---|---|
| `BusinessException` | 서비스 로직에서 의도적으로 던진 예외 | 예외에 담긴 `ErrorCode` |
| `MethodArgumentNotValidException` | `@Valid` 검증 실패 | `INVALID_INPUT` |
| `HttpRequestMethodNotSupportedException` | 잘못된 HTTP 메서드 | `METHOD_NOT_ALLOWED` |
| `Exception`(그 외 전부) | NPE, DB 오류 등 예상 못 한 예외 | `SYSTEM_ERROR` |

마지막 `Exception` 핸들러가 **최후의 안전망**입니다 — 위 세 가지에 안 걸리는 모든 예외를 잡아서, 클라이언트에게는 `SYSTEM_ERROR`라는 안전한 일반 메시지만 내려주고, 실제 원인(전체 스택트레이스)은 `log.error(...)`로 서버 로그에만 남깁니다. 이 로그는 `TraceIdFilter`가 심어둔 traceId와 함께 찍히므로, 클라이언트가 응답의 `traceId`를 알려주면 로그에서 바로 원인을 찾을 수 있습니다. 이 핸들러가 없으면 처리 안 된 예외는 Spring 기본 에러 응답이 그대로 나가서 "모든 API가 동일한 응답 형식을 쓴다"는 규칙이 깨집니다.

## `logging` — 요청 추적

**`TraceIdFilter`**: 요청이 들어오면 가장 먼저 실행되는 필터입니다(`@Order(HIGHEST_PRECEDENCE)`). `X-Trace-Id` 헤더가 없거나 형식이 이상하면 새 UUID를 발급해서 MDC에 저장하고, 응답 헤더에도 같이 실어 돌려줍니다.

- 부하테스트(k6)에서 특정 요청 하나를 로그에서 추적하고 싶으면, 요청 보낼 때 `X-Trace-Id` 헤더를 직접 지정하면 그 값이 그대로 쓰입니다.
- 헤더 값은 정규식(`^[A-Za-z0-9._-]{1,64}$`)으로 검증합니다 — 클라이언트가 로그 인젝션을 노리고 이상한 문자열을 보낼 수 있어서, 형식이 안 맞으면 무시하고 새로 발급합니다.
- 요청이 끝나면 `MDC.remove()`로 반드시 지웁니다 — 톰캣 스레드가 재사용되기 때문에, 안 지우면 다음 요청이 이전 추적 ID를 이어받는 버그가 생깁니다.

## 아직 없는 것

- **`config` 서브패키지**: `package-info.java`가 이 패키지의 책임으로 명시하고 있지만, Redis/QueryDSL 같은 공통 설정 클래스는 아직 없습니다. 해당 기술을 실제로 쓰는 코드가 생길 때 추가될 예정입니다.
- `ErrorCode`의 미정의 코드들 (위 표 참고).
