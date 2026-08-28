package com.mocou.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
    ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "발급된 쿠폰을 찾을 수 없습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "멱등성 키가 다른 상태 전이에 사용되었습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 쿠폰을 사용할 수 없습니다."),
    COUPON_EXPIRED(HttpStatus.GONE, "만료된 쿠폰은 사용할 수 없습니다."),
    COUPON_ISSUE_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 준비가 완료되지 않았습니다"),
    ISSUE_CLOSED(HttpStatus.GONE, "쿠폰 발급이 종료되었습니다"),

    // 회원
    NOT_MEMBER(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다"),

    // 정합성 검증
    VERIFICATION_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "정합성 검증 결과를 찾을 수 없습니다"),
    // 검증은 300만 건을 훑느라 오래 걸린다. 같은 검증을 겹쳐 돌리면 DB만 두 배로 바쁘고
    // 결과 행도 둘로 갈리므로, 진행 중인 실행이 있으면 새 요청을 받지 않는다.
    VERIFICATION_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 정합성 검증이 있습니다"),

    // 부하 테스트 리셋
    // 되돌릴 대상은 서버가 정한다. 발급을 여는 쿠폰이 하나여야 어느 회차를 되돌릴지 정해지며,
    // 없거나 둘 이상이면 사람이 판단해야 한다.
    LOAD_TEST_TARGET_NOT_UNIQUE(HttpStatus.CONFLICT, "되돌릴 대상 쿠폰을 특정할 수 없습니다"),
    // 컨슈머가 읽어간 발급은 리셋이 끝난 뒤 DB에 들어올 수 있다. 그러면 지운 발급이 되살아난다.
    LOAD_TEST_SYNC_IN_PROGRESS(HttpStatus.CONFLICT, "아직 DB로 반영되지 않은 발급이 남아 있습니다"),
    // 종료된 회차에는 검증 대상인 더미데이터가 들어 있다. 되돌리면 복구할 방법이 재적재뿐이다.
    LOAD_TEST_TARGET_CLOSED(HttpStatus.CONFLICT, "종료된 회차는 되돌릴 수 없습니다"),
    LOAD_TEST_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 부하 테스트가 있습니다"),
    LOAD_TEST_COUPON_NOT_READY(HttpStatus.CONFLICT, "부하 테스트를 실행할 수 없는 쿠폰 상태입니다"),
    LOAD_TEST_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "부하 테스트 실행 결과를 찾을 수 없습니다"),

    // 알림 (outbox)
    // notification insert(큐잉) 자체가 실패한 경우 - uk_notification_target 중복은
    // 정상 스킵이라 여기 안 해당하고, 그 외 DB 오류(커넥션 끊김 등)만 해당한다.
    NOTIFICATION_QUEUE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "알림 큐잉에 실패했습니다"),
    // 큐잉된 알림을 폴링/발송/상태갱신하는 과정(findPending, markSentBatch,
    // incrementRetryCount, markFailed)에서 DB 오류가 난 경우.
    NOTIFICATION_DISPATCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "알림 발송 처리에 실패했습니다"),

    // 서버
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다"),
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다"),
    // 아래 둘 다 BusinessException으로 던져지지 않고 issue_failure_log.failure_reason
    // 기록용으로만 쓰인다 — 같은 발급 건이 두 단계를 거칠 수 있어 값을 나눈다.
    // 1) 메인 스트림 재시도(5회) 한도 초과 → DLQ로 이동한 시점. 아직 최종 실패가
    //    아니라 CouponIssueSyncConsumer가 여기서는 알림을 보내지 않는다.
    SYNC_RETRY_LIMIT_EXCEEDED(HttpStatus.INTERNAL_SERVER_ERROR, "발급 동기화 재시도 한도를 초과해 DLQ로 이동했습니다"),
    // 2) DLQ 복구마저 자체 한도를 넘겨 최종 포기한 시점. CouponIssueDlqRecoveryConsumer가
    //    Redis Stream 상태를 failed로 옮기고 이 사유로 기록한 뒤 회원·관리자에게 알린다.
    //    재고는 더 이상 여기서 자동 보상하지 않는다 - 관리자가 확인 후 직접 처리한다.
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "쿠폰 발급 동기화에 실패했습니다"),
    // 관리자가 DLQ 실패 목록에서 재시도를 요청했지만 해당 recordId가 이미 처리됐거나
    // (다른 관리자가 먼저 재시도) 잘못된 값인 경우.
    ISSUE_DLQ_FAILURE_NOT_FOUND(HttpStatus.NOT_FOUND, "DLQ 실패 목록에서 해당 항목을 찾을 수 없습니다");

    private final HttpStatus status;
    private final String message;
}
