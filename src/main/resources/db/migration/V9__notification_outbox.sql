-- outbox: notification 테이블 자체가 발송 대기열을 겸한다. 비즈니스 트랜잭션과
-- 같은 트랜잭션에서 PENDING으로 insert되고, NotificationDispatchConsumer가 폴링해
-- SENT/FAILED로 갱신한다. retry_count는 그 폴링 재시도 횟수를 센다. 실패 여부는
-- 별도 로그 테이블 없이 status='FAILED'만으로 표현한다.
--
-- uk_notification_target: WAS→큐 재시도/재전달로 같은 (coupon_id, member_id, type)이
-- 두 번 insert되는 걸 DB 유니크 제약으로 막는다. member_id/coupon_id가 NULL인 관리자
-- 알림은 MySQL이 NULL을 서로 다른 값으로 취급해 이 제약에 걸리지 않는다 — 관리자
-- 알림(재고 소진 등)은 여러 번 반복될 수 있으므로 의도된 동작이다.
ALTER TABLE notification
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER status,
  ADD KEY idx_notification_status (status),
  ADD CONSTRAINT uk_notification_target UNIQUE (coupon_id, member_id, type);
