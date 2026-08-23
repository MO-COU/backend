-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:issued-members
-- ARGV[1]: memberId

-- 재고 Key가 존재하지 않는 경우
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

-- 회원이 실제로 제거된 경우에만 재고 복구
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end

-- 이미 보상됐거나 해당 회원의 예약이 없는 경우
return 0