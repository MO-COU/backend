package com.mocou.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

public class RedisCouponIssueStreamIntegrationTest
        extends RedisCouponIssueIntegrationTestSupport {

    @Test
    @DisplayName("비동기 예약에 성공하면 재고와 회원과 Stream 이벤트를 함께 저장한다")
    void reservesCouponAndAppendsEvent() {
        setStock(2);
        UUID eventId = UUID.randomUUID();

        CouponReservationResult result =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        eventId);

        assertThat(result)
                .isEqualTo(CouponReservationResult.RESERVED);
        assertThat(currentStock()).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isTrue();

        List<MapRecord<String, String, String>> events =
                issueEvents();

        assertThat(events).hasSize(1);

        Map<String, String> fields =
                events.getFirst().getValue();

        assertThat(fields)
                .containsEntry("eventId", eventId.toString())
                .containsEntry(
                        "eventType",
                        "COUPON_ISSUE_RESERVED")
                .containsEntry("schemaVersion", "1")
                .containsEntry(
                        "couponId",
                        Long.toString(COUPON_ID))
                .containsEntry("memberId", "100");

        assertThat(Long.parseLong(
                fields.get("reservedAtEpochSecond")))
                .isPositive();
    }

    @Test
    @DisplayName("비동기 중복 예약은 Stream 이벤트를 추가하지 않는다")
    void doesNotAppendEventForDuplicateReservation() {
        setStock(2);

        UUID firstEventId = UUID.randomUUID();
        UUID duplicateEventId = UUID.randomUUID();

        CouponReservationResult first =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        firstEventId);

        CouponReservationResult duplicate =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        duplicateEventId);

        assertThat(first)
                .isEqualTo(CouponReservationResult.RESERVED);
        assertThat(duplicate)
                .isEqualTo(
                        CouponReservationResult.DUPLICATE_ISSUE);
        assertThat(currentStock()).isEqualTo("1");

        List<MapRecord<String, String, String>> events =
                issueEvents();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getValue())
                .containsEntry(
                        "eventId",
                        firstEventId.toString());
    }

    @Test
    @DisplayName("비동기 예약이 품절이면 Stream 이벤트를 기록하지 않는다")
    void doesNotAppendEventForSoldOutCoupon() {
        setStock(0);

        CouponReservationResult result =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        UUID.randomUUID());

        assertThat(result)
                .isEqualTo(CouponReservationResult.SOLD_OUT);
        assertThat(currentStock()).isEqualTo("0");
        assertThat(redisTemplate.hasKey(issueStreamKey()))
                .isFalse();
    }

    @Test
    @DisplayName("비동기 예약이 오픈 전이면 Stream 이벤트를 기록하지 않는다")
    void doesNotAppendEventBeforeOpenTime() {
        setStock(2);

        setIssuePeriod(
                OPEN_UNTIL_2100,
                OPEN_UNTIL_2100 + 3_600L);

        CouponReservationResult result =
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        UUID.randomUUID());

        assertThat(result)
                .isEqualTo(CouponReservationResult.NOT_OPEN_YET);
        assertThat(currentStock()).isEqualTo("2");
        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
        assertThat(redisTemplate.hasKey(issueStreamKey()))
                .isFalse();
    }

    @Test
    @DisplayName("Stream Key 타입이 잘못되면 Redis 예약 상태를 남기지 않는다")
    void doesNotReserveWhenStreamKeyHasWrongType() {
        setStock(2);

        redisTemplate.opsForValue().set(
                issueStreamKey(),
                "not-a-stream");

        assertThatThrownBy(() ->
                gateway.reserveAndAppendEvent(
                        COUPON_ID,
                        100L,
                        UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        assertThat(currentStock()).isEqualTo("2");
        assertThat(redisTemplate.opsForSet().isMember(
                issuedMembersKey(),
                "100"))
                .isFalse();
        assertThat(redisTemplate.opsForValue().get(
                issueStreamKey()))
                .isEqualTo("not-a-stream");
    }

    private List<MapRecord<String, String, String>> issueEvents() {
        return redisTemplate
                .<String, String>opsForStream()
                .range(
                        issueStreamKey(),
                        Range.unbounded());
    }
}
