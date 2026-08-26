package com.mocou.global.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeExceptionLogTest {

    @Test
    @DisplayName("예외 메시지 없이 유형 체인과 스택 프레임을 반환한다")
    void returnsTypesAndStackFramesWithoutExceptionMessages() {
        // given
        IllegalArgumentException cause =
                new IllegalArgumentException("member@example.com");
        IllegalStateException exception =
                new IllegalStateException("token=secret", cause);

        // when
        String typeChain = SafeExceptionLog.typeChain(exception);
        String stackFrames = SafeExceptionLog.stackFrames(exception);

        // then
        assertThat(typeChain)
                .isEqualTo("java.lang.IllegalStateException -> java.lang.IllegalArgumentException");
        assertThat(stackFrames)
                .contains("SafeExceptionLogTest")
                .doesNotContain("member@example.com")
                .doesNotContain("token=secret");
    }
}
