# Prometheus·Grafana 발급 현황 대시보드 설계

## 문서 범위

이 문서는 #125에서 구현할 관측 방식을 설계한다. 현재 #124 브랜치에는 Prometheus 의존성, 설정, Grafana 대시보드 파일을 추가하지 않는다.

## 목표

- 여러 애플리케이션 인스턴스의 발급 결과를 하나의 시계열로 집계한다.
- 성공·실패 처리량과 실패 사유별 변화를 시간 구간으로 조회한다.
- 실패율 급증, Redis 접근 실패, 보상 증가를 경보 조건으로 확장할 수 있게 한다.
- `coupon_id` 라벨의 카디널리티를 통제한다.

## 제안 아키텍처

```text
발급 요청 처리 애플리케이션
  → Micrometer Counter 기록
  → /actuator/prometheus
  → Prometheus scrape
  → Grafana dashboard / alert
```

Redis Hash를 매 scrape마다 읽어 Gauge로 내보내는 방식은 #124 값과 맞추기 쉽지만, scrape 요청이 Redis 부하와 결합되고 쿠폰 탐색·보존 정책이 복잡해진다. #125의 기본안은 각 애플리케이션 인스턴스가 자신이 처리한 결과를 Counter로 기록하고 Prometheus가 인스턴스별 시계열을 합산하는 방식이다.

이 기본안은 Redis Lua 판정 직후 애플리케이션이 종료되면 Redis 카운터와 Prometheus 계측 사이에 짧은 불일치가 생길 수 있다. 따라서 Redis 방식은 정확한 현재 누적값 검증에 유지하고, Prometheus 방식은 처리율과 추세 관찰에 사용한다.

## 메트릭 초안

| 이름 | 타입 | 라벨 | 설명 |
|---|---|---|---|
| `mocou_coupon_issue_results_total` | Counter | `coupon_id`, `result` | `RESERVED`, `SOLD_OUT`, `DUPLICATE_ISSUE`, `NOT_OPEN_YET`, `ISSUE_CLOSED`, `STOCK_NOT_INITIALIZED`, `METADATA_NOT_INITIALIZED` 결과 수 |
| `mocou_coupon_issue_compensations_total` | Counter | `coupon_id`, `result` | 보상 결과 수. 초기 `result` 값은 `COMPENSATED`만 허용 |
| `mocou_coupon_issue_duration_seconds` | Timer/Histogram | `result` | 발급 예약 처리 지연시간. `coupon_id`는 고카디널리티 방지를 위해 제외 |

`member_id`, `event_id`, trace ID는 메트릭 라벨로 사용하지 않는다. `coupon_id`는 활성·검증 대상 쿠폰만 계측하거나 보존 기간과 상한을 두어 무제한 증가를 막는다. 구체적인 허용 상한과 필터 설정은 #125 구현 전에 운영 쿠폰 수를 확인해 결정한다.

## Grafana 패널 초안

1. 선택 기간 전체 요청 증가량
2. 예약 성공 증가량과 성공률
3. 초당 성공·실패 처리량
4. 실패 사유별 증가량과 비율
5. 보상 처리 증가량
6. p50·p95·p99 예약 처리 지연시간
7. scrape 상태와 마지막 수집 시각

대시보드 변수는 `coupon_id`와 관찰 기간을 제공한다. 기본 refresh 주기는 5초로 시작하되 Prometheus scrape 주기보다 짧게 설정하지 않는다.

## PromQL 형태 예시

메트릭 이름과 라벨이 #125에서 확정된 뒤 provisioning 파일에 반영한다.

```promql
sum by (result) (
  increase(mocou_coupon_issue_results_total{coupon_id="$coupon_id"}[$__range])
)
```

```promql
sum(rate(mocou_coupon_issue_results_total{coupon_id="$coupon_id", result!="RESERVED"}[5m]))
/
sum(rate(mocou_coupon_issue_results_total{coupon_id="$coupon_id"}[5m]))
```

두 번째 식은 5분 실패율이다. 분모가 0인 구간의 표시 정책은 Grafana 패널에서 0 또는 데이터 없음 중 하나로 명시한다.

## #125 구현 체크리스트

- Micrometer Prometheus registry 의존성과 Actuator endpoint 노출 범위를 결정한다.
- 메트릭 기록 지점을 Lua 결과 매핑과 보상 결과 매핑에 각각 둔다.
- 허용된 `result` 값만 태그에 사용하도록 테스트한다.
- `coupon_id` 계측 대상·상한·정리 정책을 확정하고 카디널리티를 검증한다.
- Prometheus scrape 설정과 Grafana datasource·dashboard provisioning을 별도 디렉터리로 관리한다.
- 다중 인스턴스, 프로세스 재시작, scrape 누락 상황에서 PromQL 결과를 검증한다.
- Redis 대시보드의 누적값과 같은 부하 구간의 Prometheus 증가량 차이를 기록한다.

## 완료 기준

#125는 애플리케이션 테스트에서 메트릭 증가와 허용 라벨을 검증하고, 로컬 Prometheus target이 정상이며, Grafana 패널이 실제 부하 구간의 성공·실패·보상 추세를 표시할 때 완료된다.
