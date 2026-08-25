# Redis 카운터 기반 발급 현황 대시보드

## 목적

#124 대시보드는 Redis Lua가 판정한 쿠폰별 발급 결과의 현재 누적값을 개발자가 빠르게 확인하기 위한 도구다. 별도 관측 인프라 없이 애플리케이션과 Redis만으로 동작하며, 부하 테스트 중 카운터가 증가하는 모습을 5초 간격으로 보여준다.

## 데이터 흐름

```text
발급 요청
  → reserve-and-append-event.lua
  → coupon:{couponId}:issue-result-counts Hash를 원자적으로 증가
  → GET /api/admin/coupons/{couponId}/issue-result-counts
  → issue-dashboard.html이 5초마다 조회
```

예약 보상은 `compensate-coupon.lua`에서 같은 Hash의 `COMPENSATED`를 증가시킨다.

## Redis Hash 필드

| 필드 | 의미 | 요청·실패 합계 포함 |
|---|---|---|
| `RESERVED` | 예약 성공 | 전체 요청에 포함 |
| `SOLD_OUT` | 재고 소진 | 실패 및 전체 요청에 포함 |
| `DUPLICATE_ISSUE` | 같은 회원의 중복 요청 | 실패 및 전체 요청에 포함 |
| `NOT_OPEN_YET` | 발급 시작 전 요청 | 실패 및 전체 요청에 포함 |
| `ISSUE_CLOSED` | 발급 종료 후 요청 | 실패 및 전체 요청에 포함 |
| `STOCK_NOT_INITIALIZED` | Redis 재고 미초기화 | 실패 및 전체 요청에 포함 |
| `METADATA_NOT_INITIALIZED` | 발급 기간 메타데이터 미초기화 | 실패 및 전체 요청에 포함 |
| `COMPENSATED` | 예약 후속 처리 실패로 원복 | 별도 운영 지표이며 요청·실패 합계에서 제외 |

누락된 Hash 필드는 0으로 해석한다. 음수·숫자가 아닌 값, 합산 오버플로, Redis 접근 실패는 API의 `SERVICE_UNAVAILABLE` 응답으로 변환한다.

## API와 화면 동작

- API: `GET /api/admin/coupons/{couponId}/issue-result-counts`
- 화면: `http://localhost:8080/issue-dashboard.html`
- 기본 쿠폰 ID: `301`
- 자동 갱신: 5초
- 기본 테마: 라이트
- 테마 선택: 브라우저 `localStorage`에 저장

존재하지 않는 쿠폰은 404 `COUPON_NOT_FOUND`로 응답한다. 화면은 이를 네트워크 장애와 구분해 쿠폰 ID가 포함된 안내를 보여주고, 이전 쿠폰의 카운터가 남아 오해를 만들지 않도록 표시값을 0으로 초기화한다. 그 밖의 일시적 연결 오류는 마지막 정상 값을 유지하면서 오류 안내를 표시한다.

## 로컬 확인

애플리케이션을 실행하고 대시보드를 먼저 연 뒤 다음 스크립트로 발급 요청을 만든다.

```powershell
k6 run load-test/issue-dashboard.js
```

환경에 따라 시연 쿠폰 ID가 다르면 화면의 쿠폰 ID와 k6의 `COUPON_ID`를 같은 값으로 지정한다.

```powershell
k6 run -e COUPON_ID=4 load-test/issue-dashboard.js
```

## 한계

- Redis에는 현재 누적값만 있으므로 과거 시점의 변화나 초당 처리량을 복원할 수 없다.
- 화면은 단일 쿠폰을 조회하는 개발 도구이며 사용자 인증·권한 관리가 포함된 운영 콘솔이 아니다.
- Redis Hash의 보존 기간은 쿠폰 데이터 정리 정책과 함께 별도로 관리해야 한다.
- 장기간 추세, 다중 인스턴스 집계, 경보는 [Prometheus·Grafana 방식](../prometheus-grafana-issue-dashboard/README.md)의 범위다.
