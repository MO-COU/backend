# 발급 현황 대시보드 방식 비교

쿠폰 발급 결과를 관찰하는 두 가지 방식을 비교한다. 현재 #124는 Redis 원본 카운터를 직접 조회하는 개발자용 API이고, #125는 Prometheus와 Grafana를 이용한 시계열 관측 환경을 별도로 설계·구현하는 범위다.

## 한눈에 비교

| 항목 | Redis 카운터 + 조회 API (#124) | Prometheus + Grafana (#125) |
|---|---|---|
| 주 목적 | 특정 쿠폰의 현재 누적 결과를 즉시 확인 | 시간에 따른 처리량·비율·이상 징후 관찰 |
| 원본 데이터 | `coupon:{couponId}:issue-result-counts` Hash | 애플리케이션이 노출한 메트릭을 Prometheus가 주기적으로 수집 |
| 조회 방식 | API 호출자가 관리 API로 현재 누적값 조회 | Grafana가 PromQL로 Prometheus 시계열 조회 |
| 시간 축 | 현재 누적 스냅샷만 제공 | 구간별 증가량, 초당 처리량, 추세 제공 |
| 정확성 성격 | Redis Lua 판정과 같은 원자적 경로에서 증가한 값 | 계측 지점·스크레이프 간격·프로세스 재시작을 고려해야 함 |
| 운영 기능 | 연결 상태와 실패 사유 확인 | 다중 인스턴스 집계, 대시보드 공유, 경보에 적합 |
| 구성 비용 | 낮음 | Prometheus·Grafana·메트릭 설계 및 운영 필요 |
| 카디널리티 | 조회할 쿠폰 ID를 요청에 포함 | `coupon_id` 같은 라벨의 개수와 보존 정책을 제한해야 함 |
| 권장 용도 | 개발·부하 테스트 중 단일 쿠폰의 즉시 검증 | 스테이징·운영 환경의 추세 분석과 경보 |

## 선택 기준

- 부하 테스트 결과가 Redis 판정 건수와 정확히 일치하는지 바로 확인하려면 [Redis 방식](./redis-issue-dashboard/README.md)을 사용한다.
- 여러 인스턴스의 처리량, 실패율 변화, 특정 기간의 급증을 분석하려면 [Prometheus·Grafana 설계](./prometheus-grafana-issue-dashboard/README.md)를 사용한다.
- 두 방식은 서로 대체재라기보다 관점이 다르다. #124는 현재 값 검증, #125는 시간 축 관측을 담당하도록 경계를 유지한다.

## 범위 경계

현재 브랜치에서는 Redis 조회 API만 구현한다. 폴링 화면은 이 API를 소비하는 별도 프론트엔드의 범위다. Prometheus registry 의존성, `/actuator/prometheus` 노출, Prometheus 설정, Grafana provisioning과 대시보드 JSON은 #125에서 결정하고 추가한다.
