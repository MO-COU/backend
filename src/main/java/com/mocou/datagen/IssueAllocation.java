package com.mocou.datagen;

import com.mocou.lifecycle.CouponIssueStatus;
import java.time.LocalDateTime;

/**
 * 발급 한 건에 대해 결정된 내용. 배분({@link IssueAllocator})과 적재를 잇는 그릇이다.
 *
 * <p>300만 건을 한꺼번에 들고 있으면 힙이 감당하지 못하므로 회차 단위로 만들고 바로 적재한 뒤 버린다. 한 회차가 재고 수량만큼이라 청크
 * 크기로도 알맞다.
 *
 * <p>{@code expiresAt}은 담지 않는다. {@code issuedAt + 14일}로 계산되는 값이라 함께 들고 있으면 둘이 어긋날 여지만 생긴다.
 * 반대로 {@code usedAt}은 유효기간 안 어느 시점인지를 난수로 정하므로 계산으로 복원할 수 없다.
 *
 * @param couponIssueId 회차와 순번으로 계산한 값. 이력 행이 이 번호를 참조해야 하는데, {@code AUTO_INCREMENT}에 맡기면
 *     생성된 키를 다시 읽어와야 하고 배치 문장 재작성과 함께 쓰면 안정적이지 않다.
 * @param usedAt {@code USED}가 아니면 {@code null}
 */
record IssueAllocation(
        long couponIssueId,
        long couponId,
        long memberId,
        CouponIssueStatus status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt) {

    /** 발급일로부터 2주가 지나면 사용할 수 없다(FR-2.6). */
    static final int VALIDITY_DAYS = 14;

    LocalDateTime expiresAt() {
        return issuedAt.plusDays(VALIDITY_DAYS);
    }
}
