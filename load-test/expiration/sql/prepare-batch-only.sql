-- 호출 전 @run_key, @batch_count를 설정한다. 이 파일은 PERF-EXPIRATION 접두어 데이터만 만든다.
SET @coupon_name = CONCAT('PERF-EXPIRATION-', @run_key);
SET SESSION cte_max_recursion_depth = @batch_count;
INSERT INTO coupon (name, discount_rate, open_at, close_at, status)
VALUES (@coupon_name, 10, CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 1 DAY, 'OPEN');
SET @coupon_id = LAST_INSERT_ID();
INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)
VALUES (@coupon_id, @batch_count, @batch_count);

INSERT INTO member (email, name, phone)
WITH RECURSIVE sequence AS (
  SELECT 1 AS number UNION ALL SELECT number + 1 FROM sequence WHERE number < @batch_count
)
SELECT CONCAT('perf-', @run_key, '-batch-', number, '@example.invalid'), CONCAT('perf-', number), CONCAT('010-', LPAD(number, 8, '0')) FROM sequence;

INSERT INTO coupon_issue (coupon_id, member_id, status, issued_at, expires_at)
SELECT @coupon_id, member_id, 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP - INTERVAL 1 SECOND
FROM member WHERE email LIKE CONCAT('perf-', @run_key, '-batch-%@example.invalid');

INSERT INTO coupon_issue_history (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)
SELECT coupon_issue_id, 'UNISSUED', 'ISSUED', CURRENT_TIMESTAMP - INTERVAL 1 DAY, CONCAT('ISSUE:', coupon_issue_id)
FROM coupon_issue WHERE coupon_id = @coupon_id;

SELECT @coupon_id AS coupon_id;
