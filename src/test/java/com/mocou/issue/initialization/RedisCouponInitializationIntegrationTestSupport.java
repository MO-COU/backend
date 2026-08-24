package com.mocou.issue.initialization;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mocou.issue.CouponRedisKey;

@Testcontainers
abstract class RedisCouponInitializationIntegrationTestSupport {

    protected static final long COUPON_ID = 1L;
    protected static final long OPEN_AT = 1_700_000_000L;
    protected static final long CLOSE_AT = 1_800_000_000L;

    private static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redisTemplate;

    protected RedisCouponInitializationGateway gateway;

    @BeforeAll
    protected static void createRedisClient() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    protected static void destroyRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    protected void resetRedis() {
        try (RedisConnection connection =
                     connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }

        gateway = new RedisCouponInitializationGateway(
                redisTemplate);
    }

    protected String stockKey() {
        return CouponRedisKey.stock(COUPON_ID);
    }

    protected String metadataKey() {
        return CouponRedisKey.metadata(COUPON_ID);
    }

    protected String currentStock() {
        return redisTemplate.opsForValue().get(stockKey());
    }

    protected Map<Object, Object> currentMetadata() {
        return redisTemplate.opsForHash().entries(metadataKey());
    }

    protected void setMetadata(
            long openAtEpochSecond,
            long closeAtEpochSecond
    ) {
        redisTemplate.opsForHash().putAll(
                metadataKey(),
                Map.of(
                        "openAtEpochSecond",
                        Long.toString(openAtEpochSecond),
                        "closeAtEpochSecond",
                        Long.toString(closeAtEpochSecond)));
    }
}