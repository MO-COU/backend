-- 쿠폰을 기프티콘(상품 교환) 형태로 기획하여 할인율 개념이 도메인에 맞지 않는다.
-- 금액을 계산해 깎아주는 것이 아니라 상품을 교환하므로, 상품 정보는 coupon.name으로 표현한다.
ALTER TABLE coupon DROP COLUMN discount_rate;
