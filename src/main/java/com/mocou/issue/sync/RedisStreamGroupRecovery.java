package com.mocou.issue.sync;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis Stream Consumer Group의 PEL 조회 / 인수(XCLAIM) / 신규 읽기(XREADGROUP) /
 * ACK+삭제를 감싼 공용 컴포넌트.
 *
 * <p>{@link CouponIssueSyncConsumer}(메인 발급 스트림)와
 * {@link CouponIssueDlqRecoveryConsumer}(DLQ 복구)가 서로 다른 스트림·그룹을 두고
 * 완전히 같은 Redis Stream 플러밍을 반복해서 쓰므로, 어느 스트림/그룹인지는 매번
 * 호출부가 넘기는 방식으로 여기 하나에 모은다.
 */
@Component
@RequiredArgsConstructor
class RedisStreamGroupRecovery {

    private static final RedisScript<Long> MOVE_TO_DLQ_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/redis/move-to-dlq.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;

    PendingMessages pending(String streamKey, String groupName, int count) {
        return redisTemplate.<String, String>opsForStream()
                .pending(streamKey, groupName, Range.unbounded(), count);
    }

    List<MapRecord<String, String, String>> claim(
            String streamKey, String groupName, String consumerName, long minIdleMs, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream()
                .claim(streamKey, groupName, consumerName,
                        XClaimOptions.minIdle(Duration.ofMillis(minIdleMs)).ids(ids));
        return claimed == null ? List.of() : claimed;
    }

    List<MapRecord<String, String, String>> readNext(
            String streamKey, String groupName, String consumerName, int count, long blockMs) {
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        // BLOCK 0은 Redis 프로토콜상 "무한 대기"라 논블로킹과 다르다 — blockMs<=0이면 옵션 자체를 뺀다.
        if (blockMs > 0) {
            options = options.block(Duration.ofMillis(blockMs));
        }

        return redisTemplate.<String, String>opsForStream().read(
                Consumer.from(groupName, consumerName),
                options,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    }

    void acknowledgeAndDelete(String streamKey, String groupName, String[] recordIds) {
        redisTemplate.<String, String>opsForStream().acknowledge(streamKey, groupName, recordIds);
        redisTemplate.<String, String>opsForStream().delete(streamKey, recordIds);
    }

    /**
     * 이미 XCLAIM으로 인수한 엔트리를 원본 스트림에서 다른 스트림으로 원자적으로
     * 옮긴다(XADD + XACK + XDEL을 Lua 스크립트 하나로 실행) — 앱이 중간에 죽어도
     * "옮긴 곳엔 이미 들어갔는데 원본엔 안 지워진" 상태가 생기지 않는다.
     *
     * @return 실제로 옮긴 건수(원본에 엔트리가 이미 사라져 있었다면 그만큼 적을 수 있다)
     */
    long moveEntries(String sourceStreamKey, String targetStreamKey, String groupName, List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }

        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(groupName);
        args.addAll(ids);

        Long moved = redisTemplate.execute(
                MOVE_TO_DLQ_SCRIPT, List.of(sourceStreamKey, targetStreamKey), args.toArray());
        return moved == null ? 0 : moved;
    }
}
