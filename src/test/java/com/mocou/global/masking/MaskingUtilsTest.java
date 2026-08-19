package com.mocou.global.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingUtilsTest {

    @Test
    void 이메일_마스킹() {
        assertThat(MaskingUtils.maskEmail("hong123@example.com")).isEqualTo("ho*****@example.com");
        assertThat(MaskingUtils.maskEmail("ab@example.com")).isEqualTo("a*@example.com");
        assertThat(MaskingUtils.maskEmail(null)).isNull();
        assertThat(MaskingUtils.maskEmail("invalid-email")).isEqualTo("invalid-email");
    }

    @Test
    void 이름_마스킹() {
        assertThat(MaskingUtils.maskName("홍길동")).isEqualTo("홍*동");
        assertThat(MaskingUtils.maskName("홍길")).isEqualTo("홍*");
        assertThat(MaskingUtils.maskName("홍")).isEqualTo("*");
        assertThat(MaskingUtils.maskName("남궁민수")).isEqualTo("남**수");
    }

    @Test
    void 전화번호_마스킹() {
        assertThat(MaskingUtils.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(MaskingUtils.maskPhone("01012345678")).isEqualTo("010-****-5678");
        assertThat(MaskingUtils.maskPhone("unknown-format")).isEqualTo("unknown-format");
        assertThat(MaskingUtils.maskPhone(null)).isNull();
    }
}
