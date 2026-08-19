# masking 패키지

개인정보(이름·이메일·연락처)를 API 응답이나 로그에 노출할 때 가공하는 유틸입니다. (F-COM-001)

## 핵심 원칙

**DB에는 항상 원본을 저장합니다.** 마스킹은 저장 단계가 아니라, **밖으로 나가는 시점(응답 DTO 변환 / 로그 출력)에만** 적용합니다. Entity를 그대로 응답에 실어 보내지 않고, 반드시 DTO로 변환하면서 마스킹을 거치세요.

## `MaskingUtils`

상태 없는 정적 유틸 클래스입니다. 스프링 빈이 아니라서 어디서든 그냥 `import`해서 바로 호출하면 됩니다.

| 메서드 | 규칙 | 예시 |
|---|---|---|
| `maskEmail(String)` | `@` 앞부분 중 앞 2글자만 남기고 마스킹, 도메인은 그대로 | `hong123@example.com` → `ho*****@example.com` |
| `maskName(String)` | 첫 글자·마지막 글자만 남기고 가운데 마스킹 | `홍길동` → `홍*동`, `홍길` → `홍*`, `홍` → `*` |
| `maskPhone(String)` | 하이픈 유무 상관없이 인식해서 가운데 블록만 마스킹 | `010-1234-5678` → `010-****-5678` |

세 메서드 다 `null`이나 알 수 없는 형식이 들어오면 **예외를 던지지 않고 원본(또는 null)을 그대로 반환**합니다 — 마스킹 실패가 API 전체를 500 에러로 만들면 안 되기 때문입니다.

## 사용 예시

```java
public record MemberIssueResponse(String name, String email, String phone) {

    public static MemberIssueResponse from(Member member) {
        return new MemberIssueResponse(
            MaskingUtils.maskName(member.getName()),
            MaskingUtils.maskEmail(member.getEmail()),
            MaskingUtils.maskPhone(member.getPhone())
        );
    }
}
```

Entity의 getter를 컨트롤러/응답 DTO에서 직접 노출하지 말고, 이렇게 **변환 지점을 한 군데로 모아서** 마스킹을 강제하는 패턴을 권장합니다.

## 테스트

`src/test/java/com/mocou/global/masking/MaskingUtilsTest.java` — 순수 함수라 Testcontainers 없이 `./gradlew test --tests "*MaskingUtilsTest"`로 바로 검증 가능합니다.
