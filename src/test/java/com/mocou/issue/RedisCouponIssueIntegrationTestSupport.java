package com.mocou.issue;

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

@Testcontainers
abstract class RedisCouponIssueIntegrationTestSupport {

    protected static final long COUPON_ID = 1L;
    protected static final long ALWAYS_OPEN_AT = 0L;
    protected static final long OPEN_UNTIL_2100 =
            4_102_444_800L;

    private static final int REDIS_PORT = 6379;
    private static final String OPEN_AT_FIELD =
            "openAtEpochSecond";
    private static final String CLOSE_AT_FIELD =
            "closeAtEpochSecond";

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redisTemplate;

    protected RedisCouponIssueGateway gateway;

    @BeforeAll
    protected static void createRedisClient() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();

        redisTemplate =
                new StringRedisTemplate(connectionFactory);
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

        gateway = new RedisCouponIssueGateway(
                redisTemplate,
                new CouponIssueReplicationProperties(),
                new LettuceCouponIssueReplicationWaiter());

        setIssuePeriod(
                ALWAYS_OPEN_AT,
                OPEN_UNTIL_2100);
    }

    protected void setStock(int stock) {
        redisTemplate.opsForValue().set(
                stockKey(),
                Integer.toString(stock));
    }

    protected void setIssuePeriod(
            long openAtEpochSecond,
            long closeAtEpochSecond
    ) {
        redisTemplate.opsForHash().putAll(
                metadataKey(),
                Map.of(
                        OPEN_AT_FIELD,
                        Long.toString(openAtEpochSecond),
                        CLOSE_AT_FIELD,
                        Long.toString(closeAtEpochSecond)));
    }

    protected String issueResultCountsKey() {
        return CouponRedisKey.issueResultCounts(COUPON_ID);
    }

    protected String issueSequenceKey() {
        return CouponRedisKey.issueSequence(COUPON_ID);
    }

    protected long issueResultCount(String result) {
        Object value = redisTemplate.opsForHash().get(
                issueResultCountsKey(),
                result);

        if (value == null) {
            return 0L;
        }

        return Long.parseLong(value.toString());
    }

    protected String currentStock() {
        return redisTemplate.opsForValue().get(stockKey());
    }

    protected String stockKey() {
        return CouponRedisKey.stock(COUPON_ID);
    }

    protected String issuedMembersKey() {
        return CouponRedisKey.issuedMembers(COUPON_ID);
    }

    protected Double issuedMemberScore(long memberId) {
        return redisTemplate.opsForZSet().score(
                issuedMembersKey(),
                Long.toString(memberId));
    }

    protected Long issuedMemberCount() {
        return redisTemplate.opsForZSet().size(issuedMembersKey());
    }

    protected void addIssuedMember(long memberId, double issueSequence) {
        redisTemplate.opsForZSet().add(
                issuedMembersKey(),
                Long.toString(memberId),
                issueSequence);
    }

    protected String metadataKey() {
        return CouponRedisKey.metadata(COUPON_ID);
    }

    protected String issueStreamKey() {
        return CouponRedisKey.issueStream(COUPON_ID);
    }
}
