package com.mocou.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class RedisCouponIssueGatewayIntegrationTest {

    private static final long COUPON_ID = 1L;
    private static final int REDIS_PORT = 6379;

    private static final long ALWAYS_OPEN_AT = 0L;
    private static final long OPEN_UNTIL_2100 = 4_102_444_800L;

    private static final String OPEN_AT_FIELD =
            "openAtEpochSecond";
    private static final String CLOSE_AT_FIELD =
            "closeAtEpochSecond";

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisCouponIssueGateway gateway;

    @BeforeAll
    static void createRedisClient() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void destroyRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetRedis() {
        try (RedisConnection connection =
                     connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }

        gateway = new RedisCouponIssueGateway(redisTemplate);

        setIssuePeriod(
                ALWAYS_OPEN_AT,
                OPEN_UNTIL_2100);
    }

    @Test
    @DisplayName("발급 예약에 성공하면 재고를 차감하고 회원을 등록한다")
    void reservesCoupon() {
        setStock(2);

        CouponReservationResult result = gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.RESERVED);

        assertThat(currentStock()).isEqualTo("1");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isTrue();
    }

    @Test
    @DisplayName("동일 회원의 중복 발급 예약을 차단한다")
    void rejectsDuplicateReservation() {
        setStock(2);

        CouponReservationResult first = gateway.reserve(COUPON_ID, 100L);

        CouponReservationResult duplicate = gateway.reserve(COUPON_ID, 100L);

        assertThat(first).isEqualTo(CouponReservationResult.RESERVED);

        assertThat(duplicate).isEqualTo(CouponReservationResult.DUPLICATE_ISSUE);

        assertThat(currentStock()).isEqualTo("1");

        assertThat(redisTemplate.opsForSet().size(
                issuedMembersKey()))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("재고가 없으면 발급 회원을 등록하지 않는다")
    void rejectsSoldOutCoupon() {
        setStock(0);

        CouponReservationResult result = gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.SOLD_OUT);

        assertThat(currentStock()).isEqualTo("0");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
    }

    @Test
    @DisplayName("재고 Key가 초기화되지 않으면 별도 결과를 반환한다")
    void reportsMissingStock() {
        CouponReservationResult result = gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.STOCK_NOT_INITIALIZED);

        assertThat(redisTemplate.hasKey(
                issuedMembersKey()))
                .isFalse();
    }

    @Test
    @DisplayName("쿠폰 발급 시간 Metadata가 없으면 별도 결과를 반환한다")
    void reportsMissingMetadata() {
        setStock(2);
        redisTemplate.delete(metadataKey());

        CouponReservationResult result = gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.METADATA_NOT_INITIALIZED);

        assertThat(currentStock()).isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
    }

    @Test
    @DisplayName("쿠폰 발급 시작 전에는 예약하지 않는다")
    void rejectsBeforeOpenTime() {
        setStock(2);

        setIssuePeriod(
                OPEN_UNTIL_2100,
                OPEN_UNTIL_2100 + 3_600L);

        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.NOT_OPEN_YET);

        assertThat(currentStock()).isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
    }

    @Test
    @DisplayName("쿠폰 발급 종료 시각 이후에는 예약하지 않는다")
    void rejectsAfterCloseTime() {
        setStock(2);
        setIssuePeriod(0L, 1L);

        CouponReservationResult result = gateway.reserve(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponReservationResult.ISSUE_CLOSED);

        assertThat(currentStock()).isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
    }

    @Test
    @DisplayName("보상은 회원 제거와 재고 복구를 한 번만 수행한다")
    void compensatesReservationOnlyOnce() {
        setStock(2);
        gateway.reserve(COUPON_ID, 100L);

        CouponCompensationResult first = gateway.compensate(COUPON_ID, 100L);

        CouponCompensationResult second = gateway.compensate(COUPON_ID, 100L);

        assertThat(first).isEqualTo(CouponCompensationResult.COMPENSATED);

        assertThat(second).isEqualTo(CouponCompensationResult.NOT_NEEDED);

        assertThat(currentStock()).isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
    }

    @Test
    @DisplayName("재고 Key가 없으면 보상을 수행하지 않는다")
    void reportsMissingStockDuringCompensation() {
        CouponCompensationResult result = gateway.compensate(COUPON_ID, 100L);

        assertThat(result).isEqualTo(CouponCompensationResult.STOCK_NOT_INITIALIZED);
    }

    private void setStock(int stock) {
        redisTemplate.opsForValue().set(
                stockKey(),
                Integer.toString(stock));
    }

    private void setIssuePeriod(
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

    private String currentStock() {
        return redisTemplate.opsForValue().get(stockKey());
    }

    private String stockKey() {
        return CouponRedisKey.stock(COUPON_ID);
    }

    private String issuedMembersKey() {
        return CouponRedisKey.issuedMembers(COUPON_ID);
    }

    private String metadataKey() {
        return CouponRedisKey.metadata(COUPON_ID);
    }
}