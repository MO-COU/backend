package com.mocou.consistency;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerificationContextTest {

    private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    @Test
    @DisplayName("기준 시각이 없으면 거부한다")
    void refusesMissingSnapshotTime() {
        // when, then
        assertThatThrownBy(() -> new VerificationContext(null, 300, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준 시각");
    }

    /** 유예가 0이면 만료 배치 지연을 전부 위반으로 잡지만, 그 자체가 잘못된 설정은 아니다. */
    @Test
    @DisplayName("유예 0초는 허용하고 음수만 거부한다")
    void allowsZeroGraceButRefusesNegative() {
        // when, then
        assertThatCode(() -> new VerificationContext(SNAPSHOT_AT, 0, 1_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new VerificationContext(SNAPSHOT_AT, -1, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("유예");
    }

    @Test
    @DisplayName("위반 상세 상한이 0 이하면 거부한다")
    void refusesNonPositiveViolationLimit() {
        // when, then
        assertThatThrownBy(() -> new VerificationContext(SNAPSHOT_AT, 300, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상한");
    }
}
