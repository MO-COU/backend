package com.mocou.lifecycle;

import jakarta.validation.constraints.NotNull;

/** 만료 스케줄러의 다음 자동 실행 상태를 지정한다. */
public record ExpirationSchedulerStateRequest(@NotNull Boolean enabled) {}
