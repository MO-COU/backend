import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const duplicateSuccess = new Counter('duplicate_test_success_202');
const duplicateBlocked = new Counter('duplicate_test_blocked_409');
const duplicateError = new Counter('duplicate_test_error');

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '301';
const MEMBER_ID = Number(__ENV.MEMBER_ID || '999999');

if (!Number.isInteger(MEMBER_ID) || MEMBER_ID <= 0) {
    throw new Error(`MEMBER_ID는 양의 정수여야 합니다: ${__ENV.MEMBER_ID}`);
}

// 중복 발급 409는 이 테스트에서 기대하는 정상 응답이다.
http.setResponseCallback(
    http.expectedStatuses(202, 409)
);

export const options = {
    // 동일한 회원 한 명이 연속으로 열 번 요청하는 상황을 확인한다.
    vus: 1,
    iterations: 10,
    thresholds: {
        http_reqs: ['count==10'],
        checks: ['rate==1'],
        duplicate_test_success_202: ['count==1'], // 정확히 1번만 성공해야 함
        duplicate_test_blocked_409: ['count==9'], // 나머지 9번은 409 DUPLICATE로 차단되어야 함
        duplicate_test_error: ['count==0'],       // 예기치 못한 에러 0건
    },
};

export default function () {
    // 열 번 모두 같은 회원 ID를 사용해야 중복 방어를 검증할 수 있다.
    const fixedMemberId = MEMBER_ID;
    const url = `${TARGET}/api/coupons/${COUPON_ID}/issues`;
    const payload = JSON.stringify({ memberId: fixedMemberId });
    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { name: 'duplicate-test' },
        timeout: __ENV.REQUEST_TIMEOUT || '5s',
    };

    const res = http.post(url, payload, params);

    if (res.status === 202) {
        duplicateSuccess.add(1);
    } else if (res.status === 409) {
        try {
            const body = res.json();
            if (body?.error?.code === 'DUPLICATE') {
                duplicateBlocked.add(1);
            } else {
                duplicateError.add(1);
            }
        } catch (e) {
            duplicateError.add(1);
        }
    } else {
        duplicateError.add(1);
    }

    check(res, {
        '응답이 202 또는 409이다': (r) => r.status === 202 || r.status === 409,
    });

    sleep(0.1);
}
