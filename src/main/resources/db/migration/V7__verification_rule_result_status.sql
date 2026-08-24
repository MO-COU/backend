-- 규칙이 실행에 실패하면 검사 건수와 위반 건수가 모두 0이 된다.
-- 그런데 검사 대상이 없어 0인 정상 실행도 값이 같아, DB만 보고는 둘을 구분할 수 없다.
-- 애플리케이션 안에서는 구분하지만 적재하는 순간 섞이므로, 대시보드(FR-5.3)에서
-- 규칙이 통째로 깨진 실행이 "위반 0건 = 정상"으로 보이게 된다.
--
-- 기본값을 두지 않는다. 컬럼을 빠뜨린 INSERT를 CHECKED로 조용히 통과시키는 것이
-- 이 컬럼을 만든 목적과 정반대다. 기존 행이 없어 NOT NULL을 바로 걸 수 있다.
ALTER TABLE verification_rule_result
    ADD COLUMN status VARCHAR(20) NOT NULL
        COMMENT 'CHECKED: 규칙이 끝까지 실행됨 / FAILED: 실행에 실패해 판정하지 못함'
        AFTER rule_name,
    ADD COLUMN failure_reason VARCHAR(500) NULL
        COMMENT '실행 실패 사유. CHECKED면 NULL'
        AFTER violation_count;

-- 검증은 300만 건을 훑느라 1~2분이 걸린다. 실행을 시작할 때 행을 먼저 만들어 두면
-- 그동안 "돌고 있다"는 사실이 DB에 보여, 대시보드가 진행 상황을 알 수 있고 중복 실행도 막을 수 있다.
--
-- 그런데 시작 시점에는 두 값을 모른다. 스냅샷 시각은 검증 트랜잭션을 열어야 정해지고,
-- 판정은 규칙을 다 돌려야 나온다. NOT NULL로 두면 가짜 값을 넣어야 하고, 그러면
-- 끝날 때까지 DB가 거짓을 말한다. NULL을 "아직 정해지지 않음"으로 쓴다 —
-- 이미 NULL을 허용하는 finished_at과 같은 규약이다.
--
-- 판정에는 ERROR가 추가됐다. 규칙이 하나라도 실패하면 그 실행의 "불일치 0건"은
-- 주장으로 성립하지 않으므로, 위반이 없다는 뜻의 PASS와 구분해 기록한다.
ALTER TABLE verification_run
    MODIFY COLUMN snapshot_at DATETIME NULL
        COMMENT '검증 기준 시점(스냅샷 시각). 진행 중이면 NULL',
    MODIFY COLUMN verdict VARCHAR(20) NULL
        COMMENT '전체 판정: PASS / FAIL / ERROR(규칙 실행 실패로 판정 불가). 진행 중이면 NULL';

-- rule_name에 이후 추가된 규칙들이 빠져 있어 함께 맞춘다.
ALTER TABLE verification_rule_result
    MODIFY COLUMN rule_name VARCHAR(50) NOT NULL
        COMMENT 'DUPLICATE_ISSUE, OVER_ISSUE, STOCK_MISMATCH, ORPHAN_REFERENCE, STATE_TIMESTAMP_MISMATCH, HISTORY_MISMATCH, REDIS_DB_MISMATCH, TOOL_RELIABILITY';
