package com.mocou.issue.initialization;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mocou.issue.CouponRedisKey;
import com.mocou.support.MySqlContainerTest;

@Testcontainers
abstract class CouponRedisInitializationServiceIntegrationTestSupport
        extends MySqlContainerTest {

    protected static final long COUPON_ID = 1001L;
    protected static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 20, 10, 0);
    protected static final LocalDateTime CLOSE_AT = LocalDateTime.of(2026, 8, 20, 11, 0);
    protected static final ZoneId COUPON_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine")).withExposedPorts(REDIS_PORT);

    @Autowired
    protected CouponRedisInitializationService service;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.data.redis.host",
                REDIS::getHost);
        registry.add(
                "spring.data.redis.port",
                () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @BeforeEach
    void resetRedis() {
        try (RedisConnection connection =
                     redisTemplate.getConnectionFactory()
                             .getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    protected void insertCoupon() {
        jdbcTemplate.update(
                """
                INSERT INTO coupon (
                    coupon_id,
                    name,
                    open_at,
                    close_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                COUPON_ID,
                "Redis 초기화 통합 테스트 쿠폰",
                OPEN_AT,
                CLOSE_AT,
                "READY");
    }

    protected void insertCouponStock(
            int remainingQuantity
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO coupon_stock (
                    coupon_id,
                    total_quantity,
                    remaining_quantity
                )
                VALUES (?, ?, ?)
                """,
                COUPON_ID,
                1000,
                remainingQuantity);
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

    protected long expectedOpenAtEpochSecond() {
        return OPEN_AT
                .atZone(COUPON_TIME_ZONE)
                .toEpochSecond();
    }

    protected long expectedCloseAtEpochSecond() {
        return CLOSE_AT
                .atZone(COUPON_TIME_ZONE)
                .toEpochSecond();
    }
}