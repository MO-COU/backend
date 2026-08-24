-- ============================================================
-- 선착순 쿠폰 발급 시스템 - 전체 스키마 (10개 테이블)
--
-- [테이블 관계 요약]
--   member            1 --- N  coupon_issue        (coupon_issue.member_id)
--   member            1 --- N  notification         (notification.member_id)
--   coupon            1 --- N  coupon_issue        (coupon_issue.coupon_id)
--   coupon            1 --- 1  coupon_stock        (coupon_stock.coupon_id, UNIQUE)
--   coupon            1 --- N  notification         (notification.coupon_id)
--   coupon_issue      1 --- N  coupon_issue_history (coupon_issue_history.coupon_issue_id)
--   verification_run  1 --- N  verification_rule_result (verification_rule_result.run_id)
--   verification_rule_result 1 --- N verification_violation (verification_violation.rule_result_id)
--   issue_failure_log : 어떤 테이블과도 FK 없음 (존재하지 않는 coupon_id/member_id도 기록해야 하므로 참조 무결성 미적용)
--   verification_* 그룹 : 핵심 도메인 그룹(member~notification)과 FK로 연결되지 않음
-- ============================================================

CREATE TABLE member (
    member_id   BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='가상 회원';

CREATE TABLE coupon (
    coupon_id      BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(255) NOT NULL,
    discount_rate  INT          NOT NULL,
    open_at        DATETIME     NOT NULL COMMENT '쿠폰 발급 시작 일시',
    close_at       DATETIME     NOT NULL COMMENT '쿠폰 발급 종료 일시',
    status         VARCHAR(20)  NOT NULL COMMENT 'SCHEDULED / OPEN / CLOSED',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='쿠폰/캠페인 정의';

CREATE TABLE coupon_stock (
    coupon_stock_id      BIGINT    NOT NULL AUTO_INCREMENT,
    coupon_id            BIGINT    NOT NULL,
    total_quantity       INT       NOT NULL COMMENT '최초 발급 가능 수량',
    remaining_quantity   INT       NOT NULL COMMENT 'coupon_issue INSERT와 같은 트랜잭션으로 갱신',
    updated_at           DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (coupon_stock_id),
    UNIQUE KEY uk_stock_coupon (coupon_id),
    CONSTRAINT fk_stock_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='쿠폰별 재고 (쿠폰 1:1)';

CREATE TABLE coupon_issue (
    coupon_issue_id  BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_id        BIGINT       NOT NULL,
    member_id        BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL COMMENT 'ISSUED, USED, EXPIRED',
    issued_at        DATETIME     NOT NULL,
    used_at          DATETIME     NULL,
    expires_at       DATETIME     NOT NULL COMMENT '발급 시점에 계산해 저장 (issued_at + 유효기간)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (coupon_issue_id),
    UNIQUE KEY uk_issue_coupon_member (coupon_id, member_id),
    KEY idx_issue_member (member_id),
    KEY idx_issue_status_expires (status, expires_at),
    CONSTRAINT fk_issue_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id),
    CONSTRAINT fk_issue_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='쿠폰 발급 이력 - UNIQUE(coupon_id, member_id)로 1인 1매 최종 보증';

CREATE TABLE coupon_issue_history (
    history_id         BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_issue_id     BIGINT       NOT NULL,
    from_status         VARCHAR(20)  NULL COMMENT '최초 발급이면 NULL',
    to_status           VARCHAR(20)  NOT NULL,
    changed_at          DATETIME     NOT NULL,
    idempotency_key     VARCHAR(64)  NOT NULL COMMENT '클라이언트가 생성하는 상태변경 요청 멱등성 키',
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_history_issue_idempotency (coupon_issue_id, idempotency_key),
    CONSTRAINT fk_history_issue
        FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue (coupon_issue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='발급 건 상태 변경 이력 - 동일 요청 재시도 시 UNIQUE로 1회만 반영';

CREATE TABLE notification (
    notification_id  BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_id        BIGINT       NOT NULL,
    member_id        BIGINT       NOT NULL,
    type             VARCHAR(30)  NOT NULL COMMENT '예: OPEN_SOON',
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING, SENT, FAILED',
    sent_at          DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id),
    KEY idx_notification_coupon (coupon_id),
    KEY idx_notification_member (member_id),
    CONSTRAINT fk_notification_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id),
    CONSTRAINT fk_notification_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock 알림 발송 로그';

CREATE TABLE issue_failure_log (
    failure_log_id   BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_id        BIGINT       NULL COMMENT 'FK 제약 없음 - COUPON_NOT_FOUND 등 존재하지 않는 값도 기록 가능해야 함',
    member_id        BIGINT       NULL COMMENT 'FK 제약 없음 - 위와 동일한 이유',
    failure_reason   VARCHAR(30)  NOT NULL COMMENT 'SOLD_OUT, DUPLICATE_ISSUE, NOT_OPEN_YET, COUPON_NOT_FOUND, INTERNAL_ERROR, SERVICE_UNAVAILABLE',
    occurred_at      DATETIME     NOT NULL,
    PRIMARY KEY (failure_log_id),
    KEY idx_failure_coupon_occurred (coupon_id, occurred_at),
    KEY idx_failure_reason_occurred (failure_reason, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='발급 실패 로그 (실패 원인별 통계 조회 적용 시)';

CREATE TABLE verification_run (
    run_id       BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_at  DATETIME     NOT NULL COMMENT '검증 기준 시점(스냅샷 시각)',
    verdict      VARCHAR(20)  NOT NULL COMMENT '전체 판정: PASS / FAIL',
    started_at   DATETIME     NOT NULL,
    finished_at  DATETIME     NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정합성 검증 실행 1회 단위';

CREATE TABLE verification_rule_result (
    rule_result_id   BIGINT       NOT NULL AUTO_INCREMENT,
    run_id           BIGINT       NOT NULL,
    rule_name        VARCHAR(50)  NOT NULL COMMENT 'DUPLICATE_ISSUE, OVER_ISSUE, STOCK_MISMATCH, STATE_TIMESTAMP_MISMATCH, REDIS_DB_MISMATCH, TOOL_RELIABILITY',
    checked_count    BIGINT       NOT NULL COMMENT '해당 규칙으로 검사한 총 건수',
    violation_count  BIGINT       NOT NULL COMMENT '위반 검출 건수 (정상 케이스는 0)',
    PRIMARY KEY (rule_result_id),
    KEY idx_vrr_run_id (run_id),
    CONSTRAINT fk_vrr_run
        FOREIGN KEY (run_id) REFERENCES verification_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='실행(run) 내 규칙별 검증 집계 결과';

CREATE TABLE verification_violation (
    violation_id    BIGINT       NOT NULL AUTO_INCREMENT,
    rule_result_id  BIGINT       NOT NULL,
    target_type     VARCHAR(30)  NOT NULL COMMENT 'COUPON, COUPON_ISSUE, COUPON_MEMBER_PAIR 등 위반 대상 종류',
    target_id       BIGINT       NULL COMMENT '위반 대상의 1차 식별자 (예: coupon_id, coupon_issue_id)',
    target_id2      BIGINT       NULL COMMENT '쌍(pair) 위반의 2차 식별자 (예: member_id) - 단일 대상 위반이면 NULL',
    detail          VARCHAR(500) NOT NULL COMMENT '위반 상세 설명 (예: 재고 불일치 수치, 중복 발급 건수 등)',
    detected_at     DATETIME     NOT NULL,
    PRIMARY KEY (violation_id),
    KEY idx_vv_rule_result_id (rule_result_id),
    KEY idx_vv_target (target_type, target_id),
    CONSTRAINT fk_vv_rule_result
        FOREIGN KEY (rule_result_id) REFERENCES verification_rule_result (rule_result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='위반 발생 시에만 row 생성되는 상세 기록 (정상 케이스는 비어 있음)';
