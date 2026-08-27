package com.mocou.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import com.mocou.support.MySqlContainerTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 회차를 만들면 <b>바로 발급받을 수 있는 상태</b>가 되는지 본다.
 *
 * <p>DB에만 회차가 생기고 Redis 키가 없으면 그 회차는 발급 요청을 전건 거부한다. 발급 경로가 Redis 재고 키로만 동작하기
 * 때문이다. 실제로 시연 회차가 그 상태여서 정합성 검증이 위반을 검출했고, 원인을 찾는 데 시간을 썼다.
 */
@SpringBootTest(properties = "spring.batch.jdbc.initialize-schema=never")
class CouponRoundIntegrationTest extends MySqlContainerTest {

    private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 26, 10, 0);
    private static final int TOTAL_QUANTITY = 10_000;
    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired private CouponRoundService couponRoundService;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("회차를 만들면 Redis 재고와 발급 시각까지 세워진다")
    void createsRoundReadyToIssue() {
        // when
        CouponRoundResponse response =
                couponRoundService.create(
                        new CouponRoundRequest(TOTAL_QUANTITY, OPEN_AT, null, null));

        // then - DB
        assertThat(response.totalQuantity()).isEqualTo(TOTAL_QUANTITY);
        assertThat(remainingQuantity(response.couponId())).isEqualTo(TOTAL_QUANTITY);
        assertThat(status(response.couponId())).isEqualTo("OPEN");

        // then - Redis. 이것이 없으면 그 회차는 발급 요청을 전건 거부한다
        assertThat(redisTemplate.opsForValue().get(CouponRedisKey.stock(response.couponId())))
                .isEqualTo(String.valueOf(TOTAL_QUANTITY));
        assertThat(redisTemplate.opsForHash().get(CouponRedisKey.metadata(response.couponId()), "openAtEpochSecond"))
                .isNotNull();
    }

    /** 기존 회차 300개가 모두 "당일 10시에 열어 그날 안에 닫는" 모양이라 같은 모형을 따른다. */
    @Test
    @DisplayName("발급 종료 시각을 비우면 시작 당일 자정 직전이 된다")
    void fillsCloseAtWithEndOfOpeningDay() {
        // when
        CouponRoundResponse response =
                couponRoundService.create(
                        new CouponRoundRequest(TOTAL_QUANTITY, OPEN_AT, null, null));

        // then - 하루를 더하는 것이 아니다
        assertThat(response.closeAt()).isEqualTo(LocalDateTime.of(2026, 8, 26, 23, 59, 59));
    }

    @Test
    @DisplayName("이름을 비우면 회차 번호로 만든다")
    void fillsNameWithRoundNumber() {
        // when
        CouponRoundResponse response =
                couponRoundService.create(
                        new CouponRoundRequest(TOTAL_QUANTITY, OPEN_AT, null, null));

        // then
        assertThat(response.name()).isEqualTo("아메리카노 무료 쿠폰 %d회차".formatted(response.couponId()));
    }

    @Test
    @DisplayName("요청한 값이 있으면 기본값을 쓰지 않는다")
    void keepsGivenValues() {
        // given
        LocalDateTime closeAt = OPEN_AT.plusHours(2);

        // when
        CouponRoundResponse response =
                couponRoundService.create(
                        new CouponRoundRequest(500, OPEN_AT, closeAt, "직접 지은 이름"));

        // then
        assertThat(response.closeAt()).isEqualTo(closeAt);
        assertThat(response.name()).isEqualTo("직접 지은 이름");
        assertThat(response.totalQuantity()).isEqualTo(500);
    }

    /** 회차마다 번호가 하나씩 올라가야 이름과 Redis 키가 겹치지 않는다. */
    @Test
    @DisplayName("회차를 이어 만들면 번호가 하나씩 올라간다")
    void assignsConsecutiveRoundNumbers() {
        // when
        long first = couponRoundService.create(
                        new CouponRoundRequest(TOTAL_QUANTITY, OPEN_AT, null, null)).couponId();
        long second = couponRoundService.create(
                        new CouponRoundRequest(TOTAL_QUANTITY, OPEN_AT, null, null)).couponId();

        // then
        assertThat(second).isEqualTo(first + 1);
    }

    /**
     * Redis 초기화 스크립트도 같은 검사를 하지만 거기까지 가면 DB에는 이미 회차가 들어간 뒤다. 회차만 만들어지고 Redis가 비어
     * "열려 있는데 아무도 받을 수 없는" 상태가 된다.
     */
    @Test
    @DisplayName("발급 종료가 시작보다 앞서면 회차를 만들지 않는다")
    void rejectsCloseBeforeOpen() {
        // when, then
        assertThatThrownBy(
                        () ->
                                couponRoundService.create(
                                        new CouponRoundRequest(
                                                TOTAL_QUANTITY, OPEN_AT, OPEN_AT.minusHours(1), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(count("SELECT COUNT(*) FROM coupon")).isZero();
    }

    /** 오픈 시각을 지금으로 주면 즉시 열린 회차가 된다. "만들고 바로 부하 주기"에 쓴다. */
    @Test
    @DisplayName("오픈 시각이 과거여도 회차를 만든다")
    void allowsPastOpenTime() {
        // when
        CouponRoundResponse response =
                couponRoundService.create(
                        new CouponRoundRequest(
                                TOTAL_QUANTITY, LocalDateTime.now().minusHours(1), null, null));

        // then
        assertThat(status(response.couponId())).isEqualTo("OPEN");
    }

    private String status(long couponId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM coupon WHERE coupon_id = ?", String.class, couponId);
    }

    private int remainingQuantity(long couponId) {
        Integer value =
                jdbcTemplate.queryForObject(
                        "SELECT remaining_quantity FROM coupon_stock WHERE coupon_id = ?",
                        Integer.class,
                        couponId);
        return value == null ? 0 : value;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
