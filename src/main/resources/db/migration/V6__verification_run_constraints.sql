-- 검증 실행 기록의 제약 두 개를 요구사항 범위에 맞춘다.
-- V4에서 이 제약들을 넣은 의도 자체는 유효하다. 다만 전제가 실제 요구 범위보다 좁게 잡혀 있었다.

-- (1) verification_run.issue_run_id 를 NULL 허용으로 바꾼다.
--
-- NOT NULL 이면 검증 실행을 기록하기 위해 coupon_issue_run 행이 반드시 있어야 한다.
-- 그런데 검증 대상은 발급 실행 1회가 아니라 DB 전체 상태다.
--   FR-3.4 : 검증은 이전 발급 이력을 포함한 300만 건 전체 이력을 대상으로 수행한다.
-- 더미데이터 300만 건을 검증하는 실행에는 대응하는 발급 실행이 애초에 존재하지 않는다.
--
-- 임시 coupon_issue_run 행을 만들어 우회할 수도 있으나, 의미 없는 행이 도메인 테이블에 영구히 남고
-- UNIQUE (coupon_id) 때문에 그 쿠폰을 점유해 실제 부하 테스트를 막는다. 우회 대신 컬럼의 의미를 넓힌다.
--
--   NULL   : DB 전체를 대상으로 한 검증
--   값 있음 : 특정 발급 실행 직후 그 실행을 검증
--
-- FK 는 그대로 둔다. ON DELETE CASCADE 는 넣지 않는다 — 발급 실행을 지울 때 검증 이력이 조용히
-- 사라지면 "검증했다는 기록"의 가치가 없어진다. 데이터를 초기화할 때는
-- verification_violation -> verification_rule_result -> verification_run -> coupon_issue_run 순서를 지킨다.
--
-- MODIFY 는 컬럼 정의를 통째로 다시 쓴다. COMMENT 를 다시 적지 않으면 지워지므로 함께 옮긴다.
ALTER TABLE verification_run
    MODIFY COLUMN issue_run_id BIGINT NULL
        COMMENT '검증 대상 발급 실행. NULL 이면 DB 전체 대상 검증. coupon_issue_run.run_id 참조';

-- (2) coupon_issue_run 의 쿠폰당 실행 1회 제한을 푼다.
--
-- UNIQUE (coupon_id) 는 "이벤트당 실행 1회"를 DB 가 강제하게 한 것이었다.
-- 그런데 수용 기준은 반대로 반복 실행을 요구한다.
--   NFR-3    : 동일 조건 반복 실행 시 동일한 결과가 나와야 한다.
--   FR-2.2.1 : 동일 조건 반복 실행 시 항상 초과 발급 0건.
-- 같은 쿠폰으로 부하 테스트를 두 번 돌리면 두 번째 실행은 기록할 수 없다.
-- 반복 실행이 증명해야 할 대상인데 DB 가 그것을 막고 있었다.
--
-- 인덱스 자체는 남긴다. 쿠폰별 실행 이력 조회에 쓰이고, 무엇보다 fk_issue_run_coupon 이
-- coupon_id 인덱스를 요구한다. 대체 인덱스 없이 UNIQUE 를 먼저 지우면 다음 오류로 막힌다.
--   ERROR 1553: Cannot drop index 'uk_issue_run_coupon': needed in a foreign key constraint
-- 그래서 추가를 먼저 하고 삭제를 나중에 한다. 두 문장의 순서를 바꾸면 마이그레이션이 실패한다.
ALTER TABLE coupon_issue_run
    ADD KEY idx_issue_run_coupon (coupon_id);

ALTER TABLE coupon_issue_run
    DROP INDEX uk_issue_run_coupon;
