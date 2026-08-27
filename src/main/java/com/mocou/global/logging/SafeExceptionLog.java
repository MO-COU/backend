package com.mocou.global.logging;

import java.util.Arrays;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** 예외 메시지를 노출하지 않고 오류 유형과 발생 위치만 로그에 남기는 도우미. */
public final class SafeExceptionLog {

    private SafeExceptionLog() {}

    public static String typeChain(Throwable exception) {
        StringJoiner types = new StringJoiner(" -> ");
        Throwable current = exception;

        while (current != null) {
            types.add(current.getClass().getName());
            current = current.getCause();
        }

        return types.toString();
    }

    public static String stackFrames(Throwable exception) {
        return Arrays.stream(exception.getStackTrace())
                .map(frame -> "\tat " + frame)
                .collect(Collectors.joining("\n"));
    }
}
