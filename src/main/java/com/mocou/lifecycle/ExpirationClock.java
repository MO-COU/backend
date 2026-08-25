package com.mocou.lifecycle;

import java.time.LocalDateTime;

/** 만료 판정에 사용할 기준 시각을 제공한다. */
public interface ExpirationClock {

    LocalDateTime now();
}
