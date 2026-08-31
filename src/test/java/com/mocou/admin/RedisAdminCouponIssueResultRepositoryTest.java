package com.mocou.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisAdminCouponIssueResultRepositoryTest {

    private static final long COUPON_ID = 301L;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private RedisAdminCouponDlqFailureRepository dlqFailureRepository;

    @Test
    @DisplayName("Redis 발급 결과를 관리자 집계로 변환한다")
    void returnsIssueResultCounts() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(CouponRedisKey.issueResultCounts(COUPON_ID)))
                .willReturn(
                        Map.of(
                                "RESERVED", "8320",
                                "SOLD_OUT", "1200",
                                "DUPLICATE_ISSUE", "420",
                                "NOT_OPEN_YET", "30",
                                "ISSUE_CLOSED", "30"));
        given(dlqFailureRepository.count(COUPON_ID)).willReturn(20L);
        RedisAdminCouponIssueResultRepository repository =
                new RedisAdminCouponIssueResultRepository(redisTemplate, dlqFailureRepository);

        // when
        AdminCouponIssueResultCounts result = repository.findCounts(COUPON_ID);

        // then
        assertThat(result.totalRequests()).isEqualTo(10_000);
        assertThat(result.reserved()).isEqualTo(8_320);
        assertThat(result.failed()).isEqualTo(1_680);
        assertThat(result.soldOut()).isEqualTo(1_200);
        assertThat(result.duplicateIssue()).isEqualTo(420);
        assertThat(result.notOpenYet()).isEqualTo(30);
        assertThat(result.issueClosed()).isEqualTo(30);
        assertThat(result.dlqFailed()).isEqualTo(20);
        assertThat(result.stockNotInitialized()).isZero();
        assertThat(result.metadataNotInitialized()).isZero();
    }

    @Test
    @DisplayName("Redis 결과 값이 숫자가 아니면 조회를 거부한다")
    void rejectsInvalidIssueResultCount() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(CouponRedisKey.issueResultCounts(COUPON_ID)))
                .willReturn(Map.of("RESERVED", "broken"));
        RedisAdminCouponIssueResultRepository repository =
                new RedisAdminCouponIssueResultRepository(redisTemplate, dlqFailureRepository);

        // when, then
        assertThatThrownBy(() -> repository.findCounts(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Redis 연결에 실패하면 서비스 이용 불가로 응답한다")
    void rejectsRedisConnectionFailure() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(CouponRedisKey.issueResultCounts(COUPON_ID)))
                .willThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisAdminCouponIssueResultRepository repository =
                new RedisAdminCouponIssueResultRepository(redisTemplate, dlqFailureRepository);

        // when, then
        assertThatThrownBy(() -> repository.findCounts(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("DLQ 최종 실패 건수 조회에 실패하면 서비스 이용 불가로 응답한다")
    void rejectsDlqFailureCountFailure() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(CouponRedisKey.issueResultCounts(COUPON_ID)))
                .willReturn(Map.of("RESERVED", "8320"));
        given(dlqFailureRepository.count(COUPON_ID))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "DLQ 최종 실패 건수를 조회할 수 없습니다"));
        RedisAdminCouponIssueResultRepository repository =
                new RedisAdminCouponIssueResultRepository(redisTemplate, dlqFailureRepository);

        // when, then
        assertThatThrownBy(() -> repository.findCounts(COUPON_ID))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
