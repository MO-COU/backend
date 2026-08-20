package com.mocou.issue.sync;

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
abstract class RedisCouponIssueSyncIntegrationTestSupport {

    protected static final long COUPON_ID = 1L;

    private static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redisTemplate;

    protected RedisCouponIssueSyncGateway gateway;

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

        gateway = new RedisCouponIssueSyncGateway(redisTemplate);
    }

    protected String issueStreamKey() {
        return CouponRedisKey.issueStream(COUPON_ID);
    }
}
