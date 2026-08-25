-- k6 발급 테스트가 끝나고 Redis Stream의 DB 반영이 완료된 뒤 실행한다.
-- 실행할 때 쿠폰 ID를 넘기지 않으면 시연용 기본값 301을 사용한다.
SET @coupon_id = COALESCE(@coupon_id, 301);

-- k6 직후 빠르게 확인하는 쿠폰 단위 사전 점검이다.
-- 공식 최종 판정은 docs/b1/consistency-rules.md의 R1~R7 검증 결과를 사용한다.
SELECT
    check_name,
    expected_value,
    actual_value,
    IF(expected_value = actual_value, 'PASS', 'FAIL') AS result
FROM (
    SELECT
        '최초 재고 = DB 잔여 재고 + DB 발급 건수' AS check_name,
        CAST(cs.total_quantity AS CHAR) AS expected_value,
        CAST(cs.remaining_quantity + COUNT(ci.coupon_issue_id) AS CHAR) AS actual_value
    FROM coupon_stock cs
    LEFT JOIN coupon_issue ci ON ci.coupon_id = cs.coupon_id
    WHERE cs.coupon_id = @coupon_id
    GROUP BY cs.total_quantity, cs.remaining_quantity

    UNION ALL

    SELECT
        '동일 회원 중복 발급 0건',
        '0',
        CAST(COUNT(*) AS CHAR)
    FROM (
        SELECT member_id
        FROM coupon_issue
        WHERE coupon_id = @coupon_id
        GROUP BY member_id
        HAVING COUNT(*) > 1
    ) duplicate_members

    UNION ALL

    SELECT
        '최초 발급 이력 불일치 0건',
        '0',
        CAST(COUNT(*) AS CHAR)
    FROM (
        SELECT ci.coupon_issue_id
        FROM coupon_issue ci
        LEFT JOIN coupon_issue_history cih
          ON cih.coupon_issue_id = ci.coupon_issue_id
         AND cih.from_status = 'UNISSUED'
         AND cih.to_status = 'ISSUED'
        WHERE ci.coupon_id = @coupon_id
        GROUP BY ci.coupon_issue_id
        HAVING COUNT(cih.history_id) <> 1
    ) invalid_initial_history

    UNION ALL

    SELECT
        '잘못된 상태 시각 0건',
        '0',
        CAST(COUNT(*) AS CHAR)
    FROM coupon_issue
    WHERE coupon_id = @coupon_id
      AND (
          (status = 'USED' AND used_at IS NULL)
          OR (status <> 'USED' AND used_at IS NOT NULL)
          OR used_at < issued_at
          OR expires_at <= issued_at
      )
) verification;

-- FAIL이 나온 경우 원인을 확인하기 위한 상세 조회다.
SELECT member_id, COUNT(*) AS issue_count
FROM coupon_issue
WHERE coupon_id = @coupon_id
GROUP BY member_id
HAVING COUNT(*) > 1;

SELECT ci.coupon_issue_id, ci.member_id, ci.status, COUNT(cih.history_id) AS initial_history_count
FROM coupon_issue ci
LEFT JOIN coupon_issue_history cih
    ON cih.coupon_issue_id = ci.coupon_issue_id
   AND cih.from_status = 'UNISSUED'
   AND cih.to_status = 'ISSUED'
WHERE ci.coupon_id = @coupon_id
GROUP BY ci.coupon_issue_id, ci.member_id, ci.status
HAVING initial_history_count <> 1;
