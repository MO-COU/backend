package com.mocou.datagen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 이력을 세어 잔여 재고를 역산한다.
 *
 * <p>쿠폰을 만드는 시점에는 몇 건이 발급될지 모르므로 {@code remaining_quantity}를 {@code total_quantity}와 같게
 * 넣어 둔다. 발급을 다 적재한 뒤 실제로 들어간 행을 세어 채운다.
 *
 * <p>미리 0으로 박아 두는 방법도 있지만, 그러면 "재고를 다 소진할 것"이라는 가정을 쿠폰 생성 쪽과 배분 쪽이 함께 믿어야 한다. 한쪽만
 * 바뀌면 조용히 어긋나고, 적재가 일부 실패해도 흔적이 남지 않는다. 실제로 들어간 행을 세면 그런 이상이 잔여 재고에 그대로 드러난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StockReconciler {

    /**
     * 회차마다 UPDATE를 날리면 회차 수만큼 왕복한다. 집계를 한 번 만들어 조인하면 {@code coupon_issue}를 한 번만 훑는다.
     *
     * <p>{@code GROUP BY coupon_id}는 {@code UNIQUE (coupon_id, member_id)}의 선두 컬럼을 그대로 따라가므로
     * 인덱스만으로 셀 수 있다.
     *
     * <p>발급 이력이 없는 회차는 집계에 나타나지 않아 조인에서 빠진다. 부하 테스트용 시연 회차가 여기 해당하며, 재고가 온전히 남는다.
     */
    private static final String RECONCILE_SQL =
            "UPDATE coupon_stock s "
                    + "JOIN (SELECT coupon_id, COUNT(*) AS issued FROM coupon_issue GROUP BY coupon_id) t "
                    + "  ON t.coupon_id = s.coupon_id "
                    + "SET s.remaining_quantity = s.total_quantity - t.issued";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return 잔여 재고를 갱신한 회차 수
     */
    @Transactional
    int reconcile() {
        int updated = jdbcTemplate.update(RECONCILE_SQL);
        log.info("잔여 재고 역산 완료 ({}개 회차)", updated);
        return updated;
    }
}
