package com.mocou.issue.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.mocou.support.MySqlContainerTest;

/**
 * 회차가 지워지면 동기화 대상이 다시 정해지는지 확인한다.
 *
 * <p><b>이 테스트가 없으면 리스너를 통째로 지워도 아무것도 걸리지 않는다.</b> 동기화 모듈은
 * {@code mocou.issue.sync.enabled}가 기본 꺼짐이라 다른 테스트에서는 {@link ActiveCouponIdHolder}
 * 빈 자체가 뜨지 않기 때문이다. 그래서 이 클래스에서만 켠다 — 별도 컨텍스트로 뜨므로 다른 테스트에는
 * 영향이 없다.
 *
 * <p>지운 쿠폰을 계속 가리키면 컨슈머가 {@code NOGROUP}을 만나 그룹을 다시 만들고, 그때
 * {@code MKSTREAM}이 방금 지운 스트림 키까지 되살린다. 대상을 놓는 이 동작이 그것을 막는다.
 *
 * <p>홀더가 패키지 전용이라 같은 패키지에 둔다. 밖에서는 대상이 무엇인지 확인할 방법이 없다.
 */
@SpringBootTest(
        properties = {
            "spring.batch.jdbc.initialize-schema=never",
            "mocou.issue.sync.enabled=true"
        })
class ActiveCouponIdHolderDeleteEventTest extends MySqlContainerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 28, 12, 0);
    private static final int REDIS_PORT = 6379;

    /** 지울 회차. 번호가 작아 정렬상 먼저 뽑힌다. */
    private static final long DELETED_COUPON_ID = 1;

    /** 남을 회차. 삭제 뒤 여기로 옮겨가야 한다. */
    private static final long SURVIVING_COUPON_ID = 2;

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

    @Autowired private ActiveCouponIdHolder holder;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void seedTwoOpenRounds() {
        jdbcTemplate.update("DELETE FROM coupon_stock");
        jdbcTemplate.update("DELETE FROM coupon");

        insertOpenCoupon(DELETED_COUPON_ID);
        insertOpenCoupon(SURVIVING_COUPON_ID);
    }

    @Test
    @DisplayName("지워진 회차가 대상이었으면 남은 회차로 옮겨간다")
    void movesToTheSurvivingRound() {
        // given - 지울 회차가 대상인 상태
        eventPublisher.publishEvent(new CouponSyncTargetChangedEvent(DELETED_COUPON_ID));
        assertThat(holder.get()).isEqualTo(DELETED_COUPON_ID);

        // when - 실제 삭제와 같은 순서로, 쿠폰이 사라진 뒤에 알린다
        deleteCoupon(DELETED_COUPON_ID);
        eventPublisher.publishEvent(new CouponRoundDeletedEvent(DELETED_COUPON_ID));

        // then - 지워진 쿠폰을 계속 가리키면 스트림 키가 되살아난다
        assertThat(holder.get()).isEqualTo(SURVIVING_COUPON_ID);
    }

    @Test
    @DisplayName("남은 회차가 없으면 대상이 없는 상태가 된다")
    void clearsTargetWhenNoRoundRemains() {
        // given
        eventPublisher.publishEvent(new CouponSyncTargetChangedEvent(DELETED_COUPON_ID));

        // when - 두 회차를 모두 지운다
        deleteCoupon(DELETED_COUPON_ID);
        deleteCoupon(SURVIVING_COUPON_ID);
        eventPublisher.publishEvent(new CouponRoundDeletedEvent(DELETED_COUPON_ID));

        // then - null 이면 컨슈머가 폴링 자체를 하지 않는다
        assertThat(holder.get()).isNull();
    }

    /**
     * 지운 회차가 대상이 아니었다면 대상이 바뀌지 않아야 한다.
     *
     * <p>재도출은 정렬상 첫 회차를 고르므로, 대상이 아닌 회차를 지웠을 때 대상이 앞 번호로 끌려가면
     * 진행 중인 작업이 밀려난다. 지금은 부하 테스트 중 삭제를 막아 그 상황이 생기지 않지만, 그 가드가
     * 사라지면 이 테스트가 먼저 걸린다.
     */
    @Test
    @DisplayName("대상이 아닌 회차를 지워도 뒤 번호 대상이 유지되는지 드러낸다")
    void showsWhatHappensWhenAnotherRoundIsDeleted() {
        // given - 뒤 번호가 대상인 상태에서 앞 번호를 지운다
        eventPublisher.publishEvent(new CouponSyncTargetChangedEvent(SURVIVING_COUPON_ID));

        // when
        deleteCoupon(DELETED_COUPON_ID);
        eventPublisher.publishEvent(new CouponRoundDeletedEvent(DELETED_COUPON_ID));

        // then - 남은 OPEN 이 하나뿐이라 재도출해도 같은 대상이다
        assertThat(holder.get()).isEqualTo(SURVIVING_COUPON_ID);
    }

    private void insertOpenCoupon(long couponId) {
        jdbcTemplate.update(
                "INSERT INTO coupon (coupon_id, name, open_at, close_at, status, created_at)"
                        + " VALUES (?, ?, ?, ?, 'OPEN', ?)",
                couponId,
                "회차" + couponId,
                Timestamp.valueOf(BASE_TIME.minusDays(1)),
                Timestamp.valueOf(BASE_TIME.plusDays(365)),
                Timestamp.valueOf(BASE_TIME.minusDays(1)));
        jdbcTemplate.update(
                "INSERT INTO coupon_stock (coupon_id, total_quantity, remaining_quantity)"
                        + " VALUES (?, 10, 10)",
                couponId);
    }

    private void deleteCoupon(long couponId) {
        jdbcTemplate.update("DELETE FROM coupon_stock WHERE coupon_id = ?", couponId);
        jdbcTemplate.update("DELETE FROM coupon WHERE coupon_id = ?", couponId);
    }
}
