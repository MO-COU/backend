package com.mocou.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 기능 명세서의 실패 코드를 한 곳에 모은 enum.
 * 새로운 실패 케이스를 만들 때 문자열을 임의로 쓰지 말고 여기에 추가해서 공유해야 함.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통 400번대
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다"),

    // 쿠폰
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 쿠폰입니다"),
    SOLD_OUT(HttpStatus.CONFLICT, "재고가 소진되었습니다"),
    DUPLICATE(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다"),
    NOT_OPEN_YET(HttpStatus.CONFLICT, "아직 발급 시작 전입니다"),

    // 회원
    NOT_MEMBER(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다"),

    // 서버
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;
}
