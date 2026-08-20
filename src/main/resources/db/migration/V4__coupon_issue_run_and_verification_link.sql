-- (1) "발급 실행"(부하테스트 1회) 결과를 저장할 곳이 ERD에 없었다.
-- requestedCount(동시 요청 수)는 coupon/coupon_stock 어디에도 저장되지 않는 실행 시점 입력값이고,
-- issuedCount/failedCount/status/시작·종료 시각도 coupon_issue·issue_failure_log를 coupon_id와
-- 시간 범위로 매번 집계해서 흉내내야 했다 — 특히 "요청 5000건이 전부 실패한 실행"은 issuedCount가
-- 0이라 "실행 안 함"과 구분이 안 되는 문제가 있었다. 실행 자체를 명시적인 row로 남긴다.
-- UNIQUE(coupon_id)로 "이벤트당 실행 1회" 규칙을 애플리케이션 코드가 아니라 DB가 직접 보장한다.
CREATE TABLE coupon_issue_run (
    run_id           BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_id        BIGINT       NOT NULL,
    requested_count  INT          NOT NULL COMMENT '실행 시점에 입력한 동시 요청 수 (이벤트에는 저장되지 않는 파라미터)',
    issued_count     INT          NOT NULL DEFAULT 0,
    failed_count     INT          NOT NULL DEFAULT 0,
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING, RUNNING, SUCCESS, FAILED',
    started_at       DATETIME     NOT NULL,
    finished_at      DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_issue_run_coupon (coupon_id) COMMENT '이벤트당 실행 1회 - DB 레벨로 강제',
    CONSTRAINT fk_issue_run_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이벤트당 1회 허용되는 발급 실행(부하테스트) 결과';

-- (2) verification_run은 어느 실행을 검증한 것인지 알 수 없었다
-- (V1 설계상 verification_* 그룹은 핵심 도메인과 FK로 연결되지 않음).
-- coupon_id가 아니라 coupon_issue_run.run_id를 가리키게 한다 — "이 실행이 정합했는가"를 검증하는
-- 것이 목적이므로, 실행 자체를 가리키는 편이 이벤트(coupon_id)를 가리키는 것보다 정확하다.
-- 같은 실행에 대해 정합성 검증은 여러 번(N회) 반복할 수 있으므로 issue_run_id에는
-- UNIQUE 제약을 두지 않는다 — verification_run : coupon_issue_run = N : 1.
ALTER TABLE verification_run
    ADD COLUMN issue_run_id BIGINT NOT NULL
        COMMENT '검증 대상 발급 실행. coupon_issue_run.run_id 참조'
        AFTER run_id,
    ADD KEY idx_verification_run_issue_run (issue_run_id),
    ADD CONSTRAINT fk_verification_run_issue_run
        FOREIGN KEY (issue_run_id) REFERENCES coupon_issue_run (run_id);
