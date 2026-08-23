package com.mocou.consistency;

/**
 * 검출된 위반 한 건. {@code verification_violation} 한 행이 된다.
 *
 * <p>위반을 고치지 않고 기록만 하므로, 나중에 원인을 조사할 사람이 이 행만 보고 대상을 찾아갈 수 있어야 한다. 그래서 식별자와 함께 수치가
 * 담긴 설명을 남긴다.
 *
 * @param targetId2 쌍(pair) 위반의 2차 식별자. 단일 대상이면 {@code null}
 * @param detail 위반 상세. 스키마가 500자로 제한한다
 */
public record Violation(ViolationTarget targetType, Long targetId, Long targetId2, String detail) {

    /** {@code verification_violation.detail} 컬럼 길이. */
    private static final int DETAIL_MAX_LENGTH = 500;

    public Violation {
        if (targetType == null) {
            throw new IllegalArgumentException("위반 대상 종류는 필수다");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("위반 상세는 필수다");
        }
        if (detail.length() > DETAIL_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "위반 상세가 %d자를 넘는다(%d자). 적재 시점에 잘리면 원인 조사에 필요한 수치가 사라진다"
                            .formatted(DETAIL_MAX_LENGTH, detail.length()));
        }
    }

    /** 단일 대상 위반. */
    public static Violation of(ViolationTarget targetType, Long targetId, String detail) {
        return new Violation(targetType, targetId, null, detail);
    }
}
