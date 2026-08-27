-- Redis Lua 가 예약 성공 순간에 확정한 두 값을 그대로 받는다.
--
-- 발급 순서를 정하는 권위는 Redis Lua 의 원자적 실행뿐이다. DB 에는 대조할 독립적인
-- 순서가 없다 - coupon_issue_id 는 컨슈머가 Stream 을 배치로 적재한 순서라 예약 순서와
-- 어긋날 수 있어 기준이 될 수 없다.
--
-- 대신 Lua 가 같은 실행 안에서 만든 두 개의 독립 카운터를 맞춰본다.
-- INCR 은 순번 키를, DECR 은 재고 키를 건드리므로 서로 다른 값이며,
-- 둘이 함께 적히면 다음이 성립해야 한다.
--
--   issue_sequence + remaining_at_issue = coupon_stock.total_quantity
--
-- 이 등식이 두 카운터가 따로 놀지 않았다는 증거다. 한 컬럼만 받으면 대조할 상대가 없어
-- 값을 옮겨 적은 것 이상이 되지 못한다.
--
-- NULL 허용은 필수다. 더미데이터 300만 건은 Redis 를 거치지 않고 직접 적재되므로 값이
-- 없다. NOT NULL 로 잡으면 기존 적재가 통째로 막힌다. 여기서 NULL 은 결측이 아니라
-- "검사 대상 아님"을 뜻하고, 검증 규칙과 관리자 조회가 이 값으로 부하 테스트분을 골라낸다.
ALTER TABLE coupon_issue
    ADD COLUMN issue_sequence BIGINT NULL
        COMMENT 'Redis INCR 로 확정된 쿠폰별 예약 순번. 더미데이터는 NULL'
        AFTER member_id,
    ADD COLUMN remaining_at_issue BIGINT NULL
        COMMENT '예약 성공 순간 Redis DECR 이 반환한 잔여 재고. 더미데이터는 NULL'
        AFTER issue_sequence;

-- 두 곳이 함께 쓴다.
--   (1) 검증 규칙의 쿠폰별 MAX(issue_sequence) / COUNT(DISTINCT issue_sequence) 집계
--   (2) 관리자 발급 목록의 WHERE coupon_id = ? ORDER BY issue_sequence DESC
--
-- coupon_id 가 앞이어야 쿠폰별 구간을 정렬 없이 훑는다. 순서를 뒤집으면 쿠폰별 그룹핑에
-- 정렬이 다시 필요해져 두 용도 모두 인덱스를 온전히 타지 못한다.
ALTER TABLE coupon_issue
    ADD KEY idx_issue_sequence (coupon_id, issue_sequence);
