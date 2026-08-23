-- 호출 전 @run_key, @coupon_id, @api_count를 설정한다.
SET SESSION cte_max_recursion_depth = @api_count;
INSERT INTO member (email, name, phone)
WITH RECURSIVE sequence AS (
  SELECT 1 AS number UNION ALL SELECT number + 1 FROM sequence WHERE number < @api_count
)
SELECT CONCAT('perf-', @run_key, '-api-', number, '@example.invalid'), CONCAT('perf-api-', number), CONCAT('011-', LPAD(number, 8, '0')) FROM sequence;
INSERT INTO coupon_issue (coupon_id, member_id, status, issued_at, expires_at)
SELECT @coupon_id, member_id, 'ISSUED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL 1 DAY
FROM member WHERE email LIKE CONCAT('perf-', @run_key, '-api-%@example.invalid');
INSERT INTO coupon_issue_history (coupon_issue_id, from_status, to_status, changed_at, idempotency_key)
SELECT coupon_issue_id, 'UNISSUED', 'ISSUED', CURRENT_TIMESTAMP, CONCAT('ISSUE:', coupon_issue_id)
FROM coupon_issue WHERE coupon_id = @coupon_id AND member_id IN (
  SELECT member_id FROM member WHERE email LIKE CONCAT('perf-', @run_key, '-api-%@example.invalid')
);
