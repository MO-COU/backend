-- 상태 이력은 전이 전·후 상태를 모두 명시한다. 최초 발급 이력도 예외가 아니며 from_status 에 UNISSUED 를 기록한다.
-- NULL 을 허용해 두면 규약을 어겨도 DB 가 막지 못하고, 검증 쿼리에서 비교 대상에서 조용히 빠진다.

-- NOT NULL 을 걸기 전에 기존 NULL 행을 먼저 채운다.
-- 남아 있으면 ALTER 가 실패하거나, strict mode 가 아닐 때 빈 문자열로 조용히 바뀐다.
UPDATE coupon_issue_history SET from_status = 'UNISSUED' WHERE from_status IS NULL;

-- 기본값은 두지 않는다. 컬럼을 빠뜨린 INSERT 를 잡는 것이 목적이라 기본값은 목적과 반대다.
ALTER TABLE coupon_issue_history
    MODIFY COLUMN from_status VARCHAR(20) NOT NULL COMMENT '전이 전 상태. 최초 발급 이력은 UNISSUED';
