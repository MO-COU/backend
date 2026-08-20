-- 관리자 대상 알림(재고 소진, 정합성 위반)은 특정 회원/쿠폰에 묶이지 않을 수 있어 NULL 허용으로 변경.
-- FK 제약은 그대로 둔다 (NULL 값은 FK 검사 대상에서 자동으로 제외됨).
ALTER TABLE notification MODIFY COLUMN coupon_id BIGINT NULL;
ALTER TABLE notification MODIFY COLUMN member_id BIGINT NULL;
