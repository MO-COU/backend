package com.mocou.datagen;

import com.mocou.lifecycle.CouponIssueStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회차 하나의 발급 내역을 결정한다. 누가 받았고, 언제 받았고, 지금 어떤 상태인지까지 정한다.
 *
 * <p>결과를 300만 건 모아두면 힙이 감당하지 못하므로 회차 단위로만 만든다. 회차 하나가 재고 수량만큼이라 적재 청크로도 알맞다.
 *
 * <p>재현성(NFR-3)을 위해 대부분을 계산으로 처리하고 난수는 사용 시각 한 곳에만 쓴다. 그 자리에서도 회차 번호에서 시드를 유도해, 회차를
 * 어떤 순서로 처리하든 같은 결과가 나오게 한다.
 */
@Component
@RequiredArgsConstructor
class IssueAllocator {

    /**
     * 당첨자를 흩뜨리는 보폭. 순번이 1 늘어날 때 회원 번호가 건너뛰는 칸 수다.
     *
     * <p>난수로 뽑고 충돌하면 다시 뽑는 방식은 쓰지 않는다. 재시도 횟수가 실행마다 달라지면 난수 소비량이 흔들려 재현성이 깨진다.
     *
     * <p>보폭이 회원 수와 서로소이면 순번이 다를 때 회원 번호도 반드시 다르다. 다만 서로소는 충돌만 막을 뿐 잘 흩어지는지는 보장하지
     * 않는다. 보폭이 회원 수의 간단한 분수 배수(1/2, 1/3 …)에 가까우면 금방 제자리로 돌아와 덩어리가 생긴다. 회원 100만 기준
     * 황금비인 0.618…에 가장 가까운 소수를 쓰는 이유가 이것이다. 황금비는 어떤 분수로도 잘 근사되지 않아 가장 늦게까지 뭉치지 않는다.
     *
     * <p>소수라서 웬만한 회원 수와 자동으로 서로소가 되지만, 배수로 설정하면 깨지므로 {@link #requireCoprimeStride}로 막는다.
     */
    private static final long MEMBER_STRIDE = 618_041L;

    /** 회차마다 당첨자 명단이 달라지도록 시작점을 옮기는 곱수. */
    private static final long ROUND_OFFSET_STRIDE = 7_919L;

    /** 선착순이라 순번이 곧 도착 순서다. 이 간격이면 1만 번째가 5분 뒤에 들어온다. */
    private static final long ARRIVAL_GAP_MILLIS = 30L;

    private static final int USED_PERCENT_SETTLED = 60;
    private static final int USED_PERCENT_ONGOING = 30;

    private final DatagenProperties properties;

    /**
     * 회차 하나의 발급 내역을 만든다.
     *
     * @param round 회차 번호. 1이 가장 오래된 회차다
     * @param openAt 회차 오픈 시각
     * @param baseTime 기준 시각. 발급도 사용도 이 시점을 넘어설 수 없다
     */
    List<IssueAllocation> allocate(int round, LocalDateTime openAt, LocalDateTime baseTime) {
        int stock = properties.roundStock();
        requireEnoughMembers(stock);
        requireCoprimeStride();

        long firstIssueId = (long) (round - 1) * stock;
        long memberOffset = (round * ROUND_OFFSET_STRIDE) % properties.memberCount();
        long arrivalGapMillis = arrivalGapMillis(openAt, baseTime, stock);
        Random random = new Random(properties.seed() + round);

        boolean ongoing = openAt.plusDays(IssueAllocation.VALIDITY_DAYS).isAfter(baseTime);
        int usedCount = stock * (ongoing ? USED_PERCENT_ONGOING : USED_PERCENT_SETTLED) / 100;

        List<IssueAllocation> allocations = new ArrayList<>(stock);
        for (int order = 1; order <= stock; order++) {
            LocalDateTime issuedAt =
                    openAt.plusNanos(order * arrivalGapMillis * 1_000_000L)
                            .truncatedTo(ChronoUnit.SECONDS);
            CouponIssueStatus status = statusOf(order, usedCount, ongoing);
            allocations.add(
                    new IssueAllocation(
                            firstIssueId + order,
                            round,
                            memberOf(order, memberOffset),
                            status,
                            issuedAt,
                            status == CouponIssueStatus.USED
                                    ? usedAt(issuedAt, baseTime, random)
                                    : null));
        }
        return allocations;
    }

    /**
     * 회차 하나에서 같은 회원이 두 번 당첨될 수 없으므로, 재고가 회원 수보다 많으면 애초에 만들 수 없다. 적재 도중 UNIQUE 위반으로
     * 터지면 원인을 찾기 어려우니 여기서 먼저 막는다.
     */
    private void requireEnoughMembers(int stock) {
        if (stock > properties.memberCount()) {
            throw new IllegalStateException(
                    "회차 재고(%d)가 회원 수(%d)보다 많아 회차당 1인 1매를 지킬 수 없다"
                            .formatted(stock, properties.memberCount()));
        }
    }

    /**
     * 보폭과 회원 수가 서로소인지 확인한다.
     *
     * <p>배수로 설정하면 한 회차의 모든 순번이 같은 회원 번호로 계산된다. 회원 수가 보폭과 같은 극단적인 경우 나머지가 항상 0이 되는
     * 식이다. 그대로 두면 적재를 한참 진행한 뒤에야 {@code UNIQUE (coupon_id, member_id)} 위반으로 회차 트랜잭션이
     * 롤백되므로, 900만 행을 쓰기 전에 여기서 막는다.
     */
    private void requireCoprimeStride() {
        int memberCount = properties.memberCount();
        long divisor = gcd(MEMBER_STRIDE, memberCount);
        if (divisor != 1) {
            throw new IllegalStateException(
                    "회원 수(%d)가 당첨자 보폭(%d)과 서로소가 아니어서(최대공약수 %d) 회차당 1인 1매를 지킬 수 없다"
                            .formatted(memberCount, MEMBER_STRIDE, divisor));
        }
    }

    /** 유클리드 호제법. 나머지가 0이 될 때까지 나누면 마지막 나눈 수가 최대공약수다. */
    private static long gcd(long a, long b) {
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    /**
     * 순번을 회원 번호로 옮긴다.
     *
     * <p>{@code MEMBER_STRIDE}가 회원 수와 서로소이므로 순번이 다르면 결과도 반드시 다르다. 회차당 1인 1매를 코드에서 보장하는
     * 방식이고, {@code UNIQUE (coupon_id, member_id)}는 그 결과를 DB가 다시 확인해 준다.
     */
    private long memberOf(int order, long memberOffset) {
        int memberCount = properties.memberCount();
        return ((order * MEMBER_STRIDE + memberOffset) % memberCount) + 1;
    }

    /**
     * 발급이 기준 시각을 넘지 않도록 도착 간격을 좁힌다.
     *
     * <p>오픈 직후에 데이터를 만들면 남은 시간이 5분도 안 될 수 있다. 그대로 두면 마지막 회차의 뒷번호가 미래에 발급된 것으로 기록된다.
     */
    private long arrivalGapMillis(LocalDateTime openAt, LocalDateTime baseTime, int stock) {
        long availableMillis = Duration.between(openAt, baseTime).toMillis();
        return Math.min(ARRIVAL_GAP_MILLIS, Math.max(availableMillis, 0) / stock);
    }

    /**
     * 상태를 순번으로 자른다. 난수로 던지면 비율이 요청한 값에서 흔들리는데, 순번으로 자르면 정확히 맞고 재현성도 함께 얻는다.
     *
     * <p>유효기간이 남은 회차는 아직 쓸 시간이 있으므로 사용률을 낮게 잡고 나머지를 ISSUED로 둔다. 기간이 지난 회차에는 ISSUED가 있을
     * 수 없다. 안 썼으면 만료됐기 때문이다.
     */
    private CouponIssueStatus statusOf(int order, int usedCount, boolean ongoing) {
        if (order <= usedCount) {
            return CouponIssueStatus.USED;
        }
        return ongoing ? CouponIssueStatus.ISSUED : CouponIssueStatus.EXPIRED;
    }

    /**
     * 발급 시각과 만료 시각 사이 어딘가. 계산으로 복원할 수 없는 값이라 이 한 곳에서만 난수를 쓴다.
     *
     * <p>유효기간이 아직 남은 회차라면 만료 시각이 미래이므로, 기준 시각을 넘겨 사용한 것으로 기록되지 않도록 창을 잘라낸다.
     */
    private LocalDateTime usedAt(LocalDateTime issuedAt, LocalDateTime baseTime, Random random) {
        LocalDateTime expiresAt = issuedAt.plusDays(IssueAllocation.VALIDITY_DAYS);
        LocalDateTime limit = expiresAt.isBefore(baseTime) ? expiresAt : baseTime;
        long windowMinutes = Duration.between(issuedAt, limit).toMinutes();
        return windowMinutes <= 0 ? issuedAt : issuedAt.plusMinutes(random.nextLong(windowMinutes));
    }
}
