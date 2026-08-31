package com.mocou.admin;

import com.mocou.global.exception.BusinessException;
import com.mocou.global.exception.ErrorCode;
import com.mocou.issue.CouponRedisKey;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisAdminCouponIssueResultRepository {

    private final StringRedisTemplate redisTemplate;
    private final RedisAdminCouponDlqFailureRepository dlqFailureRepository;

    public RedisAdminCouponIssueResultRepository(
            StringRedisTemplate redisTemplate, RedisAdminCouponDlqFailureRepository dlqFailureRepository) {
        this.redisTemplate = redisTemplate;
        this.dlqFailureRepository = dlqFailureRepository;
    }

    public AdminCouponIssueResultCounts findCounts(long couponId) {
        try {
            Map<Object, Object> counts =
                    redisTemplate.opsForHash().entries(CouponRedisKey.issueResultCounts(couponId));

            return AdminCouponIssueResultCounts.of(
                    couponId,
                    count(counts, "RESERVED"),
                    count(counts, "SOLD_OUT"),
                    count(counts, "DUPLICATE_ISSUE"),
                    count(counts, "NOT_OPEN_YET"),
                    count(counts, "ISSUE_CLOSED"),
                    count(counts, "STOCK_NOT_INITIALIZED"),
                    count(counts, "METADATA_NOT_INITIALIZED"),
                    dlqFailureRepository.count(couponId));
        } catch (DataAccessException | ArithmeticException | NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "실시간 발급 결과를 조회할 수 없습니다");
        }
    }

    private long count(Map<Object, Object> counts, String field) {
        Object value = counts.get(field);
        if (value == null) {
            return 0;
        }

        long count = Long.parseLong(value.toString());
        if (count < 0) {
            throw new NumberFormatException("negative issue result count");
        }
        return count;
    }
}
