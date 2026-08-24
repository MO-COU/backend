package com.mocou.loadtest;

/**
 * 부하 테스트 리셋 한 번의 결과.
 *
 * <p>리셋은 되돌리는 작업이라 실행 후 "정말 됐는가"를 확인할 수단이 있어야 한다. 응답이 성공 여부 하나면 호출한 쪽은 무엇이 얼마나
 * 지워졌는지 알 수 없다. <b>여기 담긴 수가 곧 확인 수단이다</b> — 부하 테스트에서 1만 건이 발급됐다면 {@code deletedIssues}가
 * 1만이어야 하고, 어긋나면 되돌리기가 온전하지 않았다는 뜻이다.
 *
 * <p>Redis 삭제는 담지 않는다. 키가 있으면 지우고 없으면 마는 동작이라 개수가 정보가 되지 않는다. 정작 중요한 것은 지운 뒤 재초기화가
 * 됐는지인데, 그것은 실패하면 예외로 드러난다.
 *
 * @param couponId 리셋한 쿠폰. 서버가 정하므로 호출한 쪽에 무엇을 건드렸는지 알린다
 * @param deletedIssues 지운 발급 건수
 * @param deletedHistories 지운 상태 이력 건수
 * @param deletedFailureLogs 지운 발급 실패 기록 건수
 * @param deletedNotifications 지운 알림 기록 건수
 * @param deletedVerificationRuns 지운 검증 실행 건수. 규칙별 결과와 위반 상세는 이 실행에 딸려 함께 사라진다
 * @param restoredStock 되돌린 잔여 재고. 삭제 건수가 아니라 복구한 값이라 이름을 달리한다
 */
public record LoadTestResetResult(
        long couponId,
        int deletedIssues,
        int deletedHistories,
        int deletedFailureLogs,
        int deletedNotifications,
        int deletedVerificationRuns,
        int restoredStock) {}
