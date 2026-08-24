package com.mocou.global.response;

import com.mocou.global.exception.ErrorCode;

/** ApiResponse.error()의 error 필드에 담기는 상세 정보. code는 ErrorCode enum 이름 그대로 내려간다. */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String customMessage) {
        return new ErrorResponse(errorCode.name(), customMessage);
    }
}
