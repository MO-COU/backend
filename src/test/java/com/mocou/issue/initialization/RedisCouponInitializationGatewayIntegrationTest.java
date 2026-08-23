package com.mocou.issue.initialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class RedisCouponInitializationGatewayIntegrationTest
        extends RedisCouponInitializationIntegrationTestSupport {

    @Test
    @DisplayName("재고와 발급 시간 Metadata를 함께 초기화한다")
    void initializesStockAndMetadata() {
        CouponRedisInitializationResult result =
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        OPEN_AT,
                        CLOSE_AT);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult.INITIALIZED);
        assertThat(currentStock()).isEqualTo("1000");
        assertThat(currentMetadata())
                .containsEntry(
                        "openAtEpochSecond",
                        Long.toString(OPEN_AT))
                .containsEntry(
                        "closeAtEpochSecond",
                        Long.toString(CLOSE_AT));
    }

    @Test
    @DisplayName("재실행해도 기존 Redis 재고와 Metadata를 덮어쓰지 않는다")
    void doesNotOverwriteExistingState() {
        gateway.initialize(
                COUPON_ID,
                1000,
                OPEN_AT,
                CLOSE_AT);

        redisTemplate.opsForValue().set(stockKey(), "700");

        CouponRedisInitializationResult result =
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        OPEN_AT + 100,
                        CLOSE_AT + 100);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult
                                .ALREADY_INITIALIZED);
        assertThat(currentStock()).isEqualTo("700");
        assertThat(currentMetadata())
                .containsEntry(
                        "openAtEpochSecond",
                        Long.toString(OPEN_AT))
                .containsEntry(
                        "closeAtEpochSecond",
                        Long.toString(CLOSE_AT));
    }

    @Test
    @DisplayName("재고 Key만 존재하면 불완전한 상태를 반환한다")
    void reportsStateWithOnlyStock() {
        redisTemplate.opsForValue().set(stockKey(), "1000");

        CouponRedisInitializationResult result =
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        OPEN_AT,
                        CLOSE_AT);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult
                                .INCONSISTENT_STATE);
        assertThat(currentStock()).isEqualTo("1000");
        assertThat(redisTemplate.hasKey(metadataKey()))
                .isFalse();
    }

    @Test
    @DisplayName("Metadata Key만 존재하면 불완전한 상태를 반환한다")
    void reportsStateWithOnlyMetadata() {
        setMetadata(OPEN_AT, CLOSE_AT);

        CouponRedisInitializationResult result =
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        OPEN_AT,
                        CLOSE_AT);

        assertThat(result)
                .isEqualTo(
                        CouponRedisInitializationResult
                                .INCONSISTENT_STATE);
        assertThat(redisTemplate.hasKey(stockKey()))
                .isFalse();
        assertThat(currentMetadata())
                .containsEntry(
                        "openAtEpochSecond",
                        Long.toString(OPEN_AT));
    }

    @Test
    @DisplayName("기존 Redis Key 타입이 잘못되면 초기화를 거부한다")
    void rejectsWrongRedisKeyType() {
        redisTemplate.opsForSet().add(
                stockKey(),
                "not-a-string");
        setMetadata(OPEN_AT, CLOSE_AT);

        assertThatThrownBy(() ->
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        OPEN_AT,
                        CLOSE_AT))
                .isInstanceOf(DataAccessException.class);

        assertThat(redisTemplate.opsForSet().members(stockKey()))
                .containsExactly("not-a-string");
    }

    @Test
    @DisplayName("잘못된 초기화 값을 Redis에 저장하지 않는다")
    void rejectsInvalidInitializationData() {
        assertThatThrownBy(() ->
                gateway.initialize(
                        COUPON_ID,
                        -1,
                        OPEN_AT,
                        CLOSE_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                gateway.initialize(
                        COUPON_ID,
                        1000,
                        CLOSE_AT,
                        OPEN_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(redisTemplate.hasKey(stockKey())).isFalse();
        assertThat(redisTemplate.hasKey(metadataKey())).isFalse();
    }

    @Test
    @DisplayName("동시에 초기화해도 한 요청만 최초 초기화에 성공한다")
    void initializesOnlyOnceConcurrently() throws Exception {
        int requestCount = 20;
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<CouponRedisInitializationResult>> futures =
                    new ArrayList<>();

            for (int index = 0;
                    index < requestCount;
                    index++) {
                futures.add(executor.submit(() -> {
                    startSignal.await();

                    return gateway.initialize(
                            COUPON_ID,
                            1000,
                            OPEN_AT,
                            CLOSE_AT);
                }));
            }

            startSignal.countDown();

            List<CouponRedisInitializationResult> results =
                    new ArrayList<>();

            for (Future<CouponRedisInitializationResult> future
                    : futures) {
                results.add(future.get());
            }

            assertThat(Collections.frequency(
                    results,
                    CouponRedisInitializationResult.INITIALIZED))
                    .isEqualTo(1);

            assertThat(Collections.frequency(
                    results,
                    CouponRedisInitializationResult
                            .ALREADY_INITIALIZED))
                    .isEqualTo(requestCount - 1);

            assertThat(currentStock()).isEqualTo("1000");
        } finally {
            executor.shutdownNow();
        }
    }
}