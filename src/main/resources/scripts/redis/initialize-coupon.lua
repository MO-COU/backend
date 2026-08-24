-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:metadata
--
-- ARGV[1]: remainingQuantity
-- ARGV[2]: openAtEpochSecond
-- ARGV[3]: closeAtEpochSecond

local stockExists = redis.call('EXISTS', KEYS[1])
local metadataExists = redis.call('EXISTS', KEYS[2])

-- 두 Key 중 하나만 존재하면 불완전한 초기화 상태
if stockExists ~= metadataExists then
    return -1
end

-- 두 Key가 모두 존재하면 기존 값을 검증하고 덮어쓰지 않는다
if stockExists == 1 then
    local stockType = redis.call('TYPE', KEYS[1]).ok
    local metadataType = redis.call('TYPE', KEYS[2]).ok

    if stockType ~= 'string' then
        return redis.error_reply(
            'coupon stock key must be a string'
        )
    end

    if metadataType ~= 'hash' then
        return redis.error_reply(
            'coupon metadata key must be a hash'
        )
    end

    local stock = redis.call('GET', KEYS[1])
    local metadata = redis.call(
        'HMGET',
        KEYS[2],
        'openAtEpochSecond',
        'closeAtEpochSecond'
    )

    local numericStock = tonumber(stock)
    local numericOpenAt = tonumber(metadata[1])
    local numericCloseAt = tonumber(metadata[2])

    if not numericStock
            or numericStock < 0
            or numericStock % 1 ~= 0 then
        return redis.error_reply(
            'existing coupon stock must be a non-negative integer'
        )
    end

    if not numericOpenAt or not numericCloseAt then
        return redis.error_reply(
            'existing coupon metadata timestamps must be numbers'
        )
    end

    if numericOpenAt >= numericCloseAt then
        return redis.error_reply(
            'existing coupon open time must be before close time'
        )
    end

    return 0
end

local remainingQuantity = tonumber(ARGV[1])
local openAtEpochSecond = tonumber(ARGV[2])
local closeAtEpochSecond = tonumber(ARGV[3])

-- Redis에 잘못된 초기값이 저장되지 않도록 방어
if not remainingQuantity
        or remainingQuantity < 0
        or remainingQuantity % 1 ~= 0 then
    return redis.error_reply(
        'remainingQuantity must be a non-negative integer'
    )
end

if not openAtEpochSecond or not closeAtEpochSecond then
    return redis.error_reply(
        'coupon metadata timestamps must be numbers'
    )
end

if openAtEpochSecond >= closeAtEpochSecond then
    return redis.error_reply(
        'coupon open time must be before close time'
    )
end

-- Lua Script 실행 중 다른 Redis 명령이 끼어들 수 없으므로
-- 재고와 Metadata가 하나의 원자적 작업으로 초기화된다
redis.call('SET', KEYS[1], ARGV[1])

redis.call(
    'HSET',
    KEYS[2],
    'openAtEpochSecond',
    ARGV[2],
    'closeAtEpochSecond',
    ARGV[3]
)

return 1