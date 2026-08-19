-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:issued-members
-- KEYS[3]: coupon:{couponId}:metadata
-- ARGV[1]: memberId

local stock = redis.call('GET', KEYS[1])

-- 재고 Key가 초기화되지 않은 경우
if not stock then
    return -2
end

-- 쿠폰 발급 시작·종료 시각 조회
local metadata = redis.call(
    'HMGET',
    KEYS[3],
    'openAtEpochSecond',
    'closeAtEpochSecond'
)

local openAtEpochSecond = metadata[1]
local closeAtEpochSecond = metadata[2]

-- Metadata Key 또는 필드가 초기화되지 않은 경우
if not openAtEpochSecond or not closeAtEpochSecond then
    return -5
end

local numericOpenAt = tonumber(openAtEpochSecond)
local numericCloseAt = tonumber(closeAtEpochSecond)

-- Metadata 값이 숫자가 아니면 Redis 데이터가 손상된 상태
if not numericOpenAt or not numericCloseAt then
    return redis.error_reply(
        'coupon metadata timestamps must be numbers'
    )
end

-- 발급 시작 시각은 종료 시각보다 앞서야 한다
if numericOpenAt >= numericCloseAt then
    return redis.error_reply(
        'coupon open time must be before close time'
    )
end

-- 애플리케이션 서버가 아닌 Redis 서버 시각을 기준으로 판정
local redisTime = redis.call('TIME')
local currentEpochSecond = tonumber(redisTime[1])

-- 아직 쿠폰 발급 시작 전
if currentEpochSecond < numericOpenAt then
    return -3
end

-- 종료 시각과 같거나 이후
if currentEpochSecond >= numericCloseAt then
    return -4
end

-- 이미 발급받은 회원인지 확인
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local numericStock = tonumber(stock)

-- 재고 값이 숫자가 아니면 Redis 데이터가 손상된 상태
if not numericStock then
    return redis.error_reply(
        'coupon stock must be a number'
    )
end

-- 재고가 소진된 경우
if numericStock <= 0 then
    return 0
end

-- 재고 차감과 발급 회원 등록을 원자적으로 처리
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

return 1