package com.mocou.lifecycle.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpirationJobRunRegistryTest {

    @Test
    @DisplayName("활성 만료 Job이 있으면 다른 runKey를 예약하지 않는다")
    void rejectsAnotherRunWhileAnActiveRunExists() {
        // given
        ExpirationJobRunRegistry registry = new ExpirationJobRunRegistry();

        // when
        ExpirationJobReservation first = registry.reserve("run-1", 2000);
        ExpirationJobReservation second = registry.reserve("run-2", 1000);

        // then
        assertThat(first.status()).isEqualTo(ExpirationJobReservationStatus.ACCEPTED);
        assertThat(second.status()).isEqualTo(ExpirationJobReservationStatus.JOB_ALREADY_RUNNING);
    }

    @Test
    @DisplayName("완료된 실행 뒤에는 새 runKey를 예약할 수 있다")
    void allowsAnotherRunAfterCompletion() {
        // given
        ExpirationJobRunRegistry registry = new ExpirationJobRunRegistry();
        registry.reserve("run-1", 2000);

        // when
        registry.complete("run-1");
        ExpirationJobReservation next = registry.reserve("run-2", 1000);

        // then
        assertThat(next.status()).isEqualTo(ExpirationJobReservationStatus.ACCEPTED);
    }
}
