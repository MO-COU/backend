-- k6 요청 종료와 DB 적재 완료를 나눠서 기록함.
ALTER TABLE coupon_issue_run
    ADD COLUMN db_sync_finished_at DATETIME NULL
        COMMENT 'Redis Stream 발급 이벤트가 DB에 모두 적재된 시각'
        AFTER finished_at;
