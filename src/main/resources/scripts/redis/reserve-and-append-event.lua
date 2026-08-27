-- KEYS[1]: coupon:{couponId}:stock
-- KEYS[2]: coupon:{couponId}:issued-members (Sorted Set)
-- KEYS[3]: coupon:{couponId}:metadata
-- KEYS[4]: coupon:{couponId}:issue-stream
-- KEYS[5]: coupon:{couponId}:issue-result-counts
-- KEYS[6]: coupon:{couponId}:issue-sequence
--
-- ARGV[1]: memberId
-- ARGV[2]: eventId
-- ARGV[3]: couponId

-- 결과 Counter Key가 존재한다면 Hash여야 한다
local resultCountsType = redis.call('TYPE', KEYS[5]).ok

if resultCountsType ~= 'none' and resultCountsType ~= 'hash' then
    return redis.error_reply(
        'coupon issue result counts key must be a hash'
    )
end

-- 발급 순번 Key가 존재한다면 String이어야 한다
local issueSequenceType = redis.call('TYPE', KEYS[6]).ok

if issueSequenceType ~= 'none'
        and issueSequenceType ~= 'string' then
    return redis.error_reply(
        'coupon issue sequence key must be a string'
    )
end

-- 발급 회원 Key가 존재한다면 Sorted Set이어야 한다
local issuedMembersType = redis.call('TYPE', KEYS[2]).ok

if issuedMembersType ~= 'none'
        and issuedMembersType ~= 'zset' then
    return redis.error_reply(
        'coupon issued members key must be a sorted set'
    )
end

-- Redis 예약 단계의 결과를 쿠폰별로 집계한다
local function countAndReturn(field, result)
    local countResult = redis.pcall(
        'HINCRBY',
        KEYS[5],
        field,
        1
    )

    if type(countResult) == 'table' and countResult.err then
        return redis.error_reply(countResult.err)
    end

    return result
end

local stock = redis.call('GET', KEYS[1])

-- 재고 Key가 초기화되지 않은 경우
if not stock then
    return countAndReturn(
        'STOCK_NOT_INITIALIZED',
        -2
    )
end

local numericStock = tonumber(stock)

-- 재고 값이 숫자가 아니면 Redis 데이터가 손상된 상태
if not numericStock then
    return redis.error_reply(
        'coupon stock must be a number'
    )
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
    return countAndReturn(
        'METADATA_NOT_INITIALIZED',
        -5
    )
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

-- Redis 서버 시각을 기준으로 판정
local redisTime = redis.call('TIME')
local currentEpochSecond = tonumber(redisTime[1])

-- 아직 쿠폰 발급 시작 전
if currentEpochSecond < numericOpenAt then
    return countAndReturn(
        'NOT_OPEN_YET',
        -3
    )
end

-- 종료 시각과 같거나 이후
if currentEpochSecond >= numericCloseAt then
    return countAndReturn(
        'ISSUE_CLOSED',
        -4
    )
end

-- 이미 발급받은 회원인지 확인
if redis.call('ZSCORE', KEYS[2], ARGV[1]) then
    return countAndReturn(
        'DUPLICATE_ISSUE',
        -1
    )
end

-- 재고가 소진된 경우
if numericStock <= 0 then
    return countAndReturn(
        'SOLD_OUT',
        0
    )
end

-- 이벤트 식별자가 없으면 잘못된 요청
if not ARGV[2] or ARGV[2] == '' then
    return redis.error_reply(
        'eventId must not be empty'
    )
end

if not ARGV[3] or ARGV[3] == '' then
    return redis.error_reply(
        'couponId must not be empty'
    )
end

-- 기존 Key가 Stream이 아닌 타입이면 쓰기 전에 차단
local streamType = redis.call('TYPE', KEYS[4]).ok

if streamType ~= 'none' and streamType ~= 'stream' then
    return redis.error_reply(
        'coupon issue stream key must be a stream'
    )
end

-- Redis 재고 차감
local remainingAtIssue = redis.call('DECR', KEYS[1])

-- 모든 Spring 인스턴스가 공유하는 Redis Lua 예약 성공 순번
local issueSequenceResult = redis.pcall(
    'INCR',
    KEYS[6]
)

if type(issueSequenceResult) == 'table'
        and issueSequenceResult.err then
    redis.call('INCR', KEYS[1])

    return redis.error_reply(issueSequenceResult.err)
end

local issueSequence = issueSequenceResult

-- 발급 성공 회원과 Redis 전역 순번을 하나의 Sorted Set에 저장
local issuedMemberResult = redis.pcall(
    'ZADD',
    KEYS[2],
    'NX',
    issueSequence,
    ARGV[1]
)

if type(issuedMemberResult) == 'table'
        and issuedMemberResult.err then
    redis.call('INCR', KEYS[1])
    redis.call('DECR', KEYS[6])

    return redis.error_reply(issuedMemberResult.err)
end

if issuedMemberResult == 0 then
    redis.call('INCR', KEYS[1])
    redis.call('DECR', KEYS[6])
    return countAndReturn(
        'DUPLICATE_ISSUE',
        -1
    )
end

-- 같은 Lua 안에서 발급 예약 이벤트 기록
local streamEntryId = redis.pcall(
    'XADD',
    KEYS[4],
    '*',
    'eventId',
    ARGV[2],
    'eventType',
    'COUPON_ISSUE_RESERVED',
    'schemaVersion',
    '2',
    'couponId',
    ARGV[3],
    'memberId',
    ARGV[1],
    'issueSequence',
    tostring(issueSequence),
    'remainingAtIssue',
    tostring(remainingAtIssue),
    'reservedAtEpochSecond',
    redisTime[1]
)

-- XADD 실패 시 같은 Script 안에서 예약 상태 원복
if type(streamEntryId) == 'table' and streamEntryId.err then
    redis.call('INCR', KEYS[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('DECR', KEYS[6])

    return redis.error_reply(streamEntryId.err)
end

local countResult = redis.pcall(
    'HINCRBY',
    KEYS[5],
    'RESERVED',
    1
)

-- Counter 기록 실패 시 예약과 Stream 이벤트까지 원복
if type(countResult) == 'table' and countResult.err then
    redis.call('INCR', KEYS[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('XDEL', KEYS[4], streamEntryId)
    redis.call('DECR', KEYS[6])

    return redis.error_reply(countResult.err)
end

return 1
