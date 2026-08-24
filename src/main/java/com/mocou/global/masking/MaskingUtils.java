package com.mocou.global.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개인정보 마스킹 유틸 (F-COM-001).
 * DB에는 원본을 그대로 저장하고, 이 유틸은 응답 DTO로 변환하거나 로그를 남기는 시점에만 호출한다.
 * 순수 함수라 상태가 없고, 스프링 빈으로 등록할 필요 없이 정적 메서드로 바로 쓴다.
 */
public final class MaskingUtils {

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^(\\d{2,3})[-\\s]?(\\d{3,4})[-\\s]?(\\d{4})$");

    private MaskingUtils() {
    }

    /**
     * 이메일 로컬파트(@ 앞부분)의 앞 2글자만 남기고 마스킹한다. 도메인은 그대로 둔다.
     * 예: hong123@example.com -> ho*****@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (local.length() <= 2) {
            return local.charAt(0) + "*".repeat(Math.max(local.length() - 1, 0)) + domain;
        }
        return local.substring(0, 2) + "*".repeat(local.length() - 2) + domain;
    }

    /**
     * 이름의 첫 글자와 마지막 글자만 남기고 가운데를 마스킹한다 (국내 서비스 통용 방식).
     * 예: 홍길동 -> 홍*동, 홍길 -> 홍*, 홍 -> *
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        int length = name.length();
        if (length == 1) {
            return "*";
        }
        if (length == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(length - 2) + name.charAt(length - 1);
    }

    /**
     * 전화번호 가운데 블록을 마스킹한다. 하이픈 유무는 상관없이 인식한다.
     * 예: 010-1234-5678 -> 010-****-5678, 01012345678 -> 010-****-5678
     * 알 수 없는 형식은 원본을 그대로 반환한다 (마스킹 실패로 예외를 던지지 않음).
     */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        Matcher matcher = PHONE_PATTERN.matcher(phone.trim());
        if (!matcher.matches()) {
            return phone;
        }
        String first = matcher.group(1);
        String middle = matcher.group(2);
        String last = matcher.group(3);
        return first + "-" + "*".repeat(middle.length()) + "-" + last;
    }
}
