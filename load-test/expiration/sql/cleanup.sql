-- 호출 전 @run_key를 설정한다. 테스트 접두어와 runKey가 일치하는 행만 삭제한다.
SET @coupon_name = CONCAT('PERF-EXPIRATION-', @run_key);
DELETE n FROM notification n
JOIN coupon c ON c.coupon_id = n.coupon_id
WHERE c.name = @coupon_name;
DELETE h FROM coupon_issue_history h
JOIN coupon_issue i ON i.coupon_issue_id = h.coupon_issue_id
JOIN coupon c ON c.coupon_id = i.coupon_id
WHERE c.name = @coupon_name;
DELETE i FROM coupon_issue i
JOIN coupon c ON c.coupon_id = i.coupon_id
WHERE c.name = @coupon_name;
DELETE s FROM coupon_stock s
JOIN coupon c ON c.coupon_id = s.coupon_id
WHERE c.name = @coupon_name;
DELETE FROM coupon WHERE name = @coupon_name;
DELETE FROM member WHERE email LIKE CONCAT('perf-', @run_key, '-%@example.invalid');
