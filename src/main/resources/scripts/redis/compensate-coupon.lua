-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:issued-members (Sorted Set)
-- KEYS[3]: coupon:{couponId}:issue-result-counts
-- ARGV[1]: memberId

-- 재고 Key가 존재하지 않는 경우
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

-- 결과 Counter Key가 존재한다면 Hash여야 한다
local resultCountsType = redis.call('TYPE', KEYS[3]).ok

if resultCountsType ~= 'none'
        and resultCountsType ~= 'hash' then
    return redis.error_reply(
        'coupon issue result counts key must be a hash'
    )
end

local issuedMembersType = redis.call('TYPE', KEYS[2]).ok

if issuedMembersType ~= 'none'
        and issuedMembersType ~= 'zset' then
    return redis.error_reply(
        'coupon issued members key must be a sorted set'
    )
end

-- 원복 실패 시 같은 발급 순번으로 복구할 수 있도록 Score를 먼저 읽는다
local issueSequence = redis.call('ZSCORE', KEYS[2], ARGV[1])

-- 이미 보상됐거나 해당 회원의 예약이 없는 경우
if not issueSequence then
    return 0
end

redis.call('ZREM', KEYS[2], ARGV[1])

-- Redis 재고 복구
local stockResult = redis.pcall(
    'INCR',
    KEYS[1]
)

-- 재고 복구 실패 시 제거했던 회원을 다시 등록
if type(stockResult) == 'table'
        and stockResult.err then
    redis.call('ZADD', KEYS[2], issueSequence, ARGV[1])

    return redis.error_reply(stockResult.err)
end

-- 실제 적용된 보상 횟수 집계
local countResult = redis.pcall(
    'HINCRBY',
    KEYS[3],
    'COMPENSATED',
    1
)

-- Counter 기록 실패 시 재고와 회원 정보를 보상 전 상태로 원복
if type(countResult) == 'table'
        and countResult.err then
    redis.call('DECR', KEYS[1])
    redis.call('ZADD', KEYS[2], issueSequence, ARGV[1])

    return redis.error_reply(countResult.err)
end

return 1
