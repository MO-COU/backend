package com.mocou.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

public class RedisCouponIssueGatewayIntegrationTest
        extends RedisCouponIssueIntegrationTestSupport {

    @Test
    @DisplayName("발급 예약에 성공하면 재고를 차감하고 회원을 등록한다")
    void reservesCoupon() {
        setStock(2);

        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(CouponReservationResult.RESERVED);
        assertThat(currentStock()).isEqualTo("1");
        assertThat(issuedMemberScore(100L)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("동일 회원의 중복 발급 예약을 차단한다")
    void rejectsDuplicateReservation() {
        setStock(2);

        CouponReservationResult first =
                gateway.reserve(COUPON_ID, 100L);
        CouponReservationResult duplicate =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(first)
                .isEqualTo(CouponReservationResult.RESERVED);
        assertThat(duplicate)
                .isEqualTo(
                        CouponReservationResult.DUPLICATE_ISSUE);
        assertThat(currentStock()).isEqualTo("1");
        assertThat(issuedMemberCount())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("재고가 없으면 발급 회원을 등록하지 않는다")
    void rejectsSoldOutCoupon() {
        setStock(0);

        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(CouponReservationResult.SOLD_OUT);
        assertThat(currentStock()).isEqualTo("0");
        assertThat(issuedMemberScore(100L)).isNull();
    }

    @Test
    @DisplayName("재고 Key가 초기화되지 않으면 별도 결과를 반환한다")
    void reportsMissingStock() {
        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(
                        CouponReservationResult.STOCK_NOT_INITIALIZED);
        assertThat(redisTemplate.hasKey(
                issuedMembersKey()))
                .isFalse();
    }

    @Test
    @DisplayName("쿠폰 발급 시간 Metadata가 없으면 별도 결과를 반환한다")
    void reportsMissingMetadata() {
        setStock(2);
        redisTemplate.delete(metadataKey());

        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(
                        CouponReservationResult
                                .METADATA_NOT_INITIALIZED);
        assertThat(currentStock()).isEqualTo("2");
        assertThat(issuedMemberScore(100L)).isNull();
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

        assertThat(result)
                .isEqualTo(CouponReservationResult.NOT_OPEN_YET);
        assertThat(currentStock()).isEqualTo("2");
        assertThat(issuedMemberScore(100L)).isNull();
    }

    @Test
    @DisplayName("쿠폰 발급 종료 시각 이후에는 예약하지 않는다")
    void rejectsAfterCloseTime() {
        setStock(2);
        setIssuePeriod(0L, 1L);

        CouponReservationResult result =
                gateway.reserve(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(CouponReservationResult.ISSUE_CLOSED);
        assertThat(currentStock()).isEqualTo("2");
        assertThat(issuedMemberScore(100L)).isNull();
    }

    @Test
    @DisplayName("보상은 재고와 회원과 Counter를 한 번만 변경한다")
    void compensatesReservationOnlyOnce() {
        setStock(2);

        CouponReservationResult reservation =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        UUID.randomUUID());

        CouponCompensationResult first =
                gateway.compensate(COUPON_ID, 100L);
        CouponCompensationResult second =
                gateway.compensate(COUPON_ID, 100L);

        assertThat(reservation)
                .isEqualTo(CouponReservationResult.RESERVED);
        assertThat(first)
                .isEqualTo(CouponCompensationResult.COMPENSATED);
        assertThat(second)
                .isEqualTo(CouponCompensationResult.NOT_NEEDED);

        assertThat(currentStock()).isEqualTo("2");
        assertThat(issuedMemberScore(100L)).isNull();

        assertThat(issueResultCount("RESERVED"))
                .isEqualTo(1L);
        assertThat(issueResultCount("COMPENSATED"))
                .isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(issueSequenceKey()))
                .isEqualTo("1");

        assertThat(
                issueResultCount("RESERVED")
                        - issueResultCount("COMPENSATED"))
                .isEqualTo(issuedMemberCount());
    }

    @Test
    @DisplayName("보상된 발급 순번은 다음 예약에 재사용하지 않는다")
    void doesNotReuseCompensatedIssueSequence() {
        setStock(2);

        gateway.reserveAndAppendEvent(
                COUPON_ID,
                100L,
                UUID.randomUUID());
        gateway.compensate(COUPON_ID, 100L);

        CouponReservationResult next =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        101L,
                        UUID.randomUUID());

        assertThat(next).isEqualTo(CouponReservationResult.RESERVED);
        assertThat(issuedMemberScore(100L)).isNull();
        assertThat(issuedMemberScore(101L)).isEqualTo(2.0);
        assertThat(redisTemplate.opsForValue().get(issueSequenceKey()))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("재고 Key가 없으면 보상을 수행하지 않는다")
    void reportsMissingStockDuringCompensation() {
        CouponCompensationResult result =
                gateway.compensate(COUPON_ID, 100L);

        assertThat(result)
                .isEqualTo(
                        CouponCompensationResult
                                .STOCK_NOT_INITIALIZED);
    }

    @Test
    @DisplayName("보상 Counter Key 타입이 잘못되면 예약 상태를 변경하지 않는다")
    void rejectsWrongCompensationCounterType() {
        setStock(1);

        addIssuedMember(100L, 1.0);
        redisTemplate.opsForValue().set(
                issueResultCountsKey(),
                "not-a-hash");

        assertThatThrownBy(() ->
                gateway.compensate(COUPON_ID, 100L))
                .isInstanceOf(DataAccessException.class);

        assertThat(currentStock()).isEqualTo("1");
        assertThat(issuedMemberScore(100L)).isEqualTo(1.0);
        assertThat(redisTemplate.opsForValue().get(
                issueResultCountsKey()))
                .isEqualTo("not-a-hash");
    }

    @Test
    @DisplayName("보상 Counter 기록 실패 시 재고와 회원 상태를 원복한다")
    void rollsBackCompensationWhenCounterUpdateFails() {
        setStock(2);

        gateway.reserveAndAppendEvent(
                COUPON_ID,
                100L,
                UUID.randomUUID());

        redisTemplate.opsForHash().put(
                issueResultCountsKey(),
                "COMPENSATED",
                "not-a-number");

        assertThatThrownBy(() ->
                gateway.compensate(COUPON_ID, 100L))
                .isInstanceOf(DataAccessException.class);

        assertThat(currentStock()).isEqualTo("1");
        assertThat(issuedMemberScore(100L)).isEqualTo(1.0);

        assertThat(issueResultCount("RESERVED"))
                .isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(
                issueResultCountsKey(),
                "COMPENSATED"))
                .isEqualTo("not-a-number");
    }

}
