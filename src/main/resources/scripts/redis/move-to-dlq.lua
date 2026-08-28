-- KEYS[1]: 원본 스트림 (예: coupon:{id}:issue-stream, coupon:{id}:issue-dlq)
-- KEYS[2]: 대상 스트림 (예: coupon:{id}:issue-dlq, coupon:{id}:issue-dlq-failed)
-- ARGV[1]: 원본 컨슈머 그룹명
-- ARGV[2..]: 옮길 레코드 id 목록 (호출 전에 이미 XCLAIM으로 인수돼 있어야 한다)
--
-- 이름은 move-to-dlq지만 "원본 스트림 -> 대상 스트림" 이동 자체가 범용이라
-- 메인 스트림→DLQ 이동뿐 아니라 DLQ→최종 실패(failed) 이동에도 그대로 쓰인다.
--
-- XADD(대상)와 XACK+XDEL(원본)을 한 스크립트 안에서 원자적으로 실행한다. Java에서
-- 따로따로 호출하면 XADD 성공 직후 프로세스가 죽었을 때 "대상엔 이미 들어갔는데
-- 원본엔 안 지워진" 상태가 생겨, 다음 tick에 같은 엔트리가 대상에 중복으로 쌓일 수
-- 있다. 이 스크립트는 Redis 입장에서 단일 명령이라 그 틈이 없다.

local sourceStream = KEYS[1]
local dlqStream = KEYS[2]
local groupName = ARGV[1]
local moved = 0

for i = 2, #ARGV do
    local id = ARGV[i]

    -- XCLAIM은 소유권만 옮길 뿐 엔트리 자체는 원본 스트림에 그대로 남아 있으므로
    -- XRANGE로 필드를 다시 읽어올 수 있다.
    local entries = redis.call('XRANGE', sourceStream, id, id)
    if #entries > 0 then
        local fields = entries[1][2]
        redis.call('XADD', dlqStream, '*', unpack(fields))
        moved = moved + 1
    end

    redis.call('XACK', sourceStream, groupName, id)
    redis.call('XDEL', sourceStream, id)
end

return moved
