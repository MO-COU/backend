# 만료 Batch 동시 부하 테스트

이 디렉터리는 **EC2 전용 테스트 서버의 Compose 호스트**에서만 실행한다. 테스트 담당자는 결과를 해석하거나 청크를 고르지 않는다. 명령 실행 뒤 출력되는 `RESULT_FILE` 내용을 결과 검토자에게 그대로 전달한다.

## 실행 순서와 역할

```text
테스트 담당자                         결과 검토자
compare-chunks 실행
result.txt 전달  ───────────────────→  A·C 비교 후 청크 선택
                                      race 명령의 청크 값을 확정
race 명령 실행
result.txt 전달  ───────────────────→  REVIEW_TEMPLATE.md에 최종 판정
```

DB는 EC2의 `docker compose` MySQL 컨테이너 안에서 직접 실행한다. DB 포트를 외부에 열거나 `.env`에 접속 비밀번호를 넣지 않는다. app 주소 기본값은 EC2 호스트의 `http://localhost:8080`이다.

## 사전 조건

- EC2 전용 테스트 서버에 SSH 접속
- 해당 디렉터리를 포함한 배포 브랜치가 서버에 있음
- app을 `perf` 프로필로 실행했고, `mocou.perf.expiration-control-enabled=true`
- `mocou.lifecycle.expiration.scheduler-enabled=false`
- 서버에 `docker`, `curl`, `jq`, `k6`, `tar`가 설치됨

스크립트는 실행 전에 health, capability, Scheduler 비활성, MySQL 접속, 필수 도구를 자동으로 확인한다. 하나라도 실패하면 부하·데이터 변경 전에 종료한다. 입력한 청크 크기도 capability가 반환한 허용 범위 안인지 데이터 준비 전에 확인한다.

정상 실행의 종료 코드는 `0`이다. 종료 코드가 `0`이 아니면 테스트 담당자는 결과를 해석하거나 임의로 재실행하지 않고, 출력된 `RESULT_FILE`과 `ARTIFACT_BUNDLE`을 결과 검토자에게 함께 전달한다. Batch 시작·대기 실패 시에도 스크립트는 실행 중인 k6를 종료하고 테스트 데이터(알림 포함)를 정리한 뒤 실패한다.

## 1단계 — A·C 청크 비교

```text
A: Batch Only
시간 ─────────────────────────────→
       B                       E
       │                       │
       ├──── chunk1 ... N ─────┤
       사용 API 없음

C: 지속 API 부하
시간 ─────────────────────────────→
       B                       E
       │                       │
       ├──── chunk1 ... N ─────┤
       ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
       B부터 E까지 사용 API 지속
```

대상은 후보별 만료 대상 10,000건이다. A는 Batch 자체 기준선을, C는 별도 API 대상에 일정한 사용 요청을 보내며 Batch·API가 동시에 동작할 때의 지연과 안정성을 확인한다.

EC2에서 아래 명령을 그대로 실행한다.

```bash
./load-test/expiration/run-expiration-test.sh \
  --scenario compare-chunks \
  --chunk-sizes 1000,2000,5000 \
  --arrival-rate 333 \
  --warmups 1 \
  --repeats 3
```

`result.txt`는 A의 Batch 시간·상태·이력, C의 Batch 시간·API p95/p99·API 성공 수와 실제 USED 수·dropped iteration·PASS/FAIL을 후보별 반복 행으로 자동 출력한다. warmup은 원시 산출물에만 남고 후보 비교 표에는 포함되지 않는다. 사용 API가 성공한 경우 `USED` 알림 기록 수도 실제 `USED` 수와 자동 대조한다. 실행 후 이 파일 전체를 복사해 결과 검토자에게 전달한다. 자동 실패 또는 검토자 요청 시 같은 경로의 `artifacts.tar.gz`도 함께 전달한다.

결과 검토자는 안정성 오류·5xx·timeout·deadlock·dropped iteration이 있는 후보를 먼저 제외한다. 남은 후보의 A 대비 C Batch 지연율, API p95/p99, Lock 대기, Connection 사용량을 비교한다. 명확한 개선이 없거나 결과가 엇갈리면 기본값 2,000을 유지한다.

## 2단계 — 선정 청크 B 정합성 검증

```text
시간 ───────────────────────────────────→
T-5초                T≈B               T+5초
 │                    │                  │
 ├─ 사용 요청 시작 ───┼── 사용 요청 지속 ─┤
                      └─ Batch 시작
                 ISSUED → USED / EXPIRED 경쟁
```

대상은 만료 시각이 같은 10,000건이며, 각 `issueId`에 사용 요청을 정확히 한 번 보낸다. T 이전에 사용을 선점한 건은 `USED`, Batch가 먼저 처리하거나 T 이후 요청된 건은 `COUPON_EXPIRED`와 `EXPIRED`가 예상된다.

결과 검토자가 선정한 실제 청크 값으로 아래 명령을 테스트 담당자에게 전달한다. 예시는 `1000`일 뿐이며, 담당자가 임의로 값을 바꾸지 않는다.

```bash
./load-test/expiration/run-expiration-test.sh \
  --scenario race \
  --chunk-size 1000 \
  --repeats 3
```

자동 확인은 요청 10,000건, `dropped_iterations=0`, `|B-T| ≤ 1초`, 예상 외 응답·5xx·timeout·deadlock 0건, 최종 `USED + EXPIRED = 10,000`, `ISSUED = 0`, 상태·이력 불일치 0건, `USED` 알림 기록 수와 `USED` 수의 일치를 모두 요구한다. 완료 후 `result.txt` 전체를 결과 검토자에게 전달한다.

## 전달 파일

```text
load-test/expiration/results/<실행시각>-<시나리오>/
├─ result.txt          # 항상 전달
├─ artifacts.tar.gz    # 자동 실패 또는 요청 시 전달
└─ raw/                # API 요청/응답, Batch 상태, k6 요약, SQL 원본
```

테스트 담당자는 결과 파일을 편집·요약하지 않는다. 결과 검토자는 새 문서를 작성하지 않고 `REVIEW_TEMPLATE.md`의 빈칸만 채운다.
