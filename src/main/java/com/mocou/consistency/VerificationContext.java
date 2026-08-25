package com.mocou.consistency;

import java.time.LocalDateTime;

/**
 * 규칙 실행 조건. 한 번의 검증 실행 동안 모든 규칙이 같은 값을 본다.
 *
 * <p>규칙마다 기준 시각과 유예를 따로 받으면 시그니처가 길어지고 순서를 헷갈린다. 무엇보다 규칙 사이에 값이 달라질 여지를 없애는 것이 목적이다.
 *
 * @param snapshotAt 판정 기준 시각. 읽기 트랜잭션의 스냅샷 시점과 같아야 한다
 * @param graceSeconds 만료 지연 유예. 만료 배치 주기에서 파생되는 값이라 상수가 아니다
 * @param violationLimit 규칙당 저장할 위반 상세의 최대 건수
 */
public record VerificationContext(
        LocalDateTime snapshotAt, long graceSeconds, int violationLimit) {

    public VerificationContext {
        if (snapshotAt == null) {
            throw new IllegalArgumentException("기준 시각은 필수다");
        }
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("만료 유예(%d초)는 음수일 수 없다".formatted(graceSeconds));
        }
        if (violationLimit < 1) {
            throw new IllegalArgumentException(
                    "위반 상세 상한(%d)은 1 이상이어야 한다. 0이면 불일치 항목을 하나도 남기지 못한다"
                            .formatted(violationLimit));
        }
    }
}
