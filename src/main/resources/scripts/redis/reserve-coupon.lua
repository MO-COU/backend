-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:issued-members
-- ARGV[1]: memberId

local stock = redis.call('GET', KEYS[1])

-- 재고 Key가 초기화되지 않은 경우
if not stock then
    return -2
end

-- 이미 발급받은 회원인지 확인
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local numericStock = tonumber(stock)

-- 재고 값이 숫자가 아니면 Redis 데이터가 손상된 상태
if not numericStock then
    return redis.error_reply('coupon stock must be a number')
end

-- 재고가 소진된 경우
if numericStock <= 0 then
    return 0
end

-- Redis 내부에서 재고 차감과 회원 등록을 원자적으로 처리
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

return 1