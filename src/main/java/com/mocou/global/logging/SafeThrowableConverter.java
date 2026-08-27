package com.mocou.global.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/** 예외 메시지 없이 예외 유형과 스택 프레임만 JSON 로그에 직렬화한다. */
public class SafeThrowableConverter extends ThrowableHandlingConverter {

    @Override
    public String convert(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable == null) {
            return "";
        }

        StringBuilder stackTrace = new StringBuilder();
        appendThrowable(stackTrace, throwable, false);
        return stackTrace.toString();
    }

    private void appendThrowable(StringBuilder stackTrace, IThrowableProxy throwable, boolean isCause) {
        if (isCause) {
            stackTrace.append("Caused by: ");
        }
        stackTrace.append(throwable.getClassName()).append('\n');

        for (StackTraceElementProxy frame : throwable.getStackTraceElementProxyArray()) {
            stackTrace.append("\tat ").append(frame.getStackTraceElement()).append('\n');
        }

        IThrowableProxy cause = throwable.getCause();
        if (cause != null) {
            appendThrowable(stackTrace, cause, true);
        }
    }
}
