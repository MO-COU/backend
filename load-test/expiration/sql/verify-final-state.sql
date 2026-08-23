-- 호출 전 @coupon_id를 설정한다. key=value 형식의 결과를 반환한다.
SELECT CONCAT('ISSUED=', COUNT(*)) FROM coupon_issue WHERE coupon_id = @coupon_id AND status = 'ISSUED';
SELECT CONCAT('USED=', COUNT(*)) FROM coupon_issue WHERE coupon_id = @coupon_id AND status = 'USED';
SELECT CONCAT('EXPIRED=', COUNT(*)) FROM coupon_issue WHERE coupon_id = @coupon_id AND status = 'EXPIRED';
SELECT CONCAT('INVALID_HISTORY=', COUNT(*))
FROM coupon_issue i
WHERE i.coupon_id = @coupon_id AND (
  SELECT COUNT(*) FROM coupon_issue_history h WHERE h.coupon_issue_id = i.coupon_issue_id
) NOT IN (1, 2);
SELECT CONCAT('INVALID_FINAL_HISTORY=', COUNT(*))
FROM coupon_issue i
WHERE i.coupon_id = @coupon_id
  AND i.status IN ('USED', 'EXPIRED')
  AND NOT EXISTS (
    SELECT 1 FROM coupon_issue_history h
    WHERE h.coupon_issue_id = i.coupon_issue_id
      AND h.from_status = 'ISSUED' AND h.to_status = i.status
  );
SELECT CONCAT('CONFLICTING_FINAL_HISTORY=', COUNT(*))
FROM coupon_issue i
WHERE i.coupon_id = @coupon_id
  AND (SELECT COUNT(DISTINCT h.to_status) FROM coupon_issue_history h
       WHERE h.coupon_issue_id = i.coupon_issue_id AND h.from_status = 'ISSUED') > 1;
SELECT CONCAT('USED_AFTER_EXPIRY=', COUNT(*))
FROM coupon_issue WHERE coupon_id = @coupon_id AND status = 'USED' AND used_at >= expires_at;
