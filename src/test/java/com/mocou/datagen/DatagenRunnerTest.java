package com.mocou.datagen;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mocou.issue.initialization.CouponRedisInitializationResult;
import com.mocou.issue.initialization.CouponRedisInitializationService;

@ExtendWith(MockitoExtension.class)
class DatagenRunnerTest {

    private static final long DEMO_COUPON_ID = 301L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 19, 0, 0);
    private static final LocalDateTime FIRST_OPEN_AT = LocalDateTime.of(2020, 11, 23, 10, 0);

    private static final List<CouponSeedSpec> ROUNDS =
            List.of(
                    new CouponSeedSpec(
                            1L,
                            "아메리카노 무료 쿠폰 1회차",
                            FIRST_OPEN_AT,
                            FIRST_OPEN_AT.withHour(23).withMinute(59).withSecond(59),
                            "CLOSED",
                            10_000),
                    new CouponSeedSpec(
                            DEMO_COUPON_ID,
                            "아메리카노 무료 쿠폰 301회차",
                            BASE_TIME.minusDays(1),
                            BASE_TIME.plusDays(365),
                            "OPEN",
                            10_000));

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DatagenProperties properties;

    @Mock
    private CouponSeeder couponSeeder;

    @Mock
    private MemberGenerator memberGenerator;

    @Mock
    private IssueGenerator issueGenerator;

    @Mock
    private StockReconciler stockReconciler;

    @Mock
    private CouponRedisInitializationService couponRedisInitializationService;

    @Mock
    private ApplicationArguments applicationArguments;

    @InjectMocks
    private DatagenRunner runner;

    @Test
    @DisplayName("더미데이터 생성과 재고 역산 후 시연용 쿠폰을 Redis에 초기화한다")
    void initializesRedisAfterStockReconciliation() {
        // given
        givenDatabaseCounts(0L, 0L);
        given(properties.baseTime()).willReturn(BASE_TIME);
        given(couponSeeder.seed(BASE_TIME)).willReturn(ROUNDS);
        given(couponSeeder.demoCouponId()).willReturn(DEMO_COUPON_ID);
        given(memberGenerator.generate(FIRST_OPEN_AT)).willReturn(1_000);
        given(issueGenerator.generate(ROUNDS, BASE_TIME, DEMO_COUPON_ID)).willReturn(10_000);
        given(stockReconciler.reconcile()).willReturn(300);
        given(couponRedisInitializationService.initialize(DEMO_COUPON_ID)).willReturn(CouponRedisInitializationResult.INITIALIZED);

        // when
        runner.run(applicationArguments);

        // then
        verify(couponSeeder).seed(BASE_TIME);
        verify(memberGenerator).generate(FIRST_OPEN_AT);
        verify(issueGenerator).generate(ROUNDS, BASE_TIME, DEMO_COUPON_ID);

        InOrder order = inOrder(stockReconciler, couponRedisInitializationService);

        order.verify(stockReconciler).reconcile();
        order.verify(couponRedisInitializationService).initialize(DEMO_COUPON_ID);
    }

    @Test
    @DisplayName("DB 데이터가 이미 있어도 시연용 쿠폰의 Redis 초기화 상태를 확인한다")
    void initializesRedisWhenDatabaseIsAlreadySeeded() {
        // given
        givenDatabaseCounts(1_000_000L, 301L);
        given(couponSeeder.demoCouponId()).willReturn(DEMO_COUPON_ID);
        given(couponRedisInitializationService.initialize(DEMO_COUPON_ID))
                .willReturn(CouponRedisInitializationResult.ALREADY_INITIALIZED);

        // when
        runner.run(applicationArguments);

        // then
        verify(couponRedisInitializationService).initialize(DEMO_COUPON_ID);

        verify(couponSeeder, never()).seed(any(LocalDateTime.class));
        verifyNoInteractions(memberGenerator, issueGenerator, stockReconciler);
    }

    @Test
    @DisplayName("Redis 재고와 Metadata가 불완전하면 데이터 생성을 실패 처리한다")
    void failsWhenRedisInitializationStateIsInconsistent() {
        // given
        givenDatabaseCounts(1_000_000L, 301L);
        given(couponSeeder.demoCouponId()).willReturn(DEMO_COUPON_ID);
        given(couponRedisInitializationService.initialize(DEMO_COUPON_ID))
                .willReturn(CouponRedisInitializationResult.INCONSISTENT_STATE);

        // when, then
        assertThatThrownBy(() -> runner.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis 초기화 상태가 불완전")
                .hasMessageContaining("couponId=" + DEMO_COUPON_ID);

        verifyNoInteractions(memberGenerator, issueGenerator, stockReconciler);
    }

    private void givenDatabaseCounts(long memberCount, long couponCount) {
        given(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member",Long.class)).willReturn(memberCount);

        given(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon",Long.class)).willReturn(couponCount);
    }
}