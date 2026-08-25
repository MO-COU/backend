-- 부하 테스트 실행 기록에 "어떤 조건으로 돌렸는지"와 k6가 재는 결과를 담는다.
-- 기존 컬럼(requested_count / issued_count / failed_count)만으로는 실행을 여러 번 했을 때
-- 무엇이 달랐는지 알 수 없다.
--
-- 조건 컬럼은 실행 시작 시점에, 결과 컬럼은 종료 시점에 값이 정해진다.
ALTER TABLE coupon_issue_run
    -- 조건: 시작할 때 채운다. k6가 보내지 않거나 손으로 실행할 수도 있어 NULL을 허용한다.
    ADD COLUMN scenario_version VARCHAR(30) NULL
        COMMENT '실행한 k6 시나리오. 숫자를 넣더라도 나중에 1-v2처럼 쓸 수 있도록 문자열로 둔다'
        AFTER coupon_id,
    ADD COLUMN vus INT NULL
        COMMENT '동시 가상 사용자 수'
        AFTER scenario_version,
    ADD COLUMN ramp_up_seconds INT NULL
        COMMENT 'VU를 목표치까지 올리는 데 쓴 시간(초)'
        AFTER vus,
    -- 결과: 종료할 때 채운다. 기존 issued_count / failed_count와 같이 0에서 시작한다.
    ADD COLUMN sold_out_count INT NOT NULL DEFAULT 0
        COMMENT '재고 소진으로 거절된 요청 수'
        AFTER failed_count,
    ADD COLUMN duplicate_count INT NOT NULL DEFAULT 0
        COMMENT '중복 발급으로 거절된 요청 수'
        AFTER sold_out_count,
    ADD COLUMN error_count INT NOT NULL DEFAULT 0
        COMMENT '5xx 등 예상하지 못한 실패 수'
        AFTER duplicate_count,
    -- 지연은 0을 기본값으로 둘 수 없다. 0건과 달리 "0ms"는 성립하지 않아
    -- 측정 전과 측정 결과를 구분해야 한다.
    ADD COLUMN p95_ms INT NULL
        COMMENT '응답 시간 95백분위(ms). 측정 전에는 NULL'
        AFTER error_count;

-- 시나리오별로 실행을 모아 보기 위한 인덱스. 리포트에서 조건을 바꿔가며 비교할 때 쓴다.
CREATE INDEX idx_issue_run_scenario ON coupon_issue_run (scenario_version);
