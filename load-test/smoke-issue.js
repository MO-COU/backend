import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const success202 = new Counter('smoke_success_202');
const duplicate409 = new Counter('smoke_duplicate_409');
const soldOut409 = new Counter('smoke_sold_out_409');
const systemError5xx = new Counter('smoke_system_error_5xx');
const otherError = new Counter('smoke_other_error');

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '301';
const VUS = Number(__ENV.VUS || '10');

// 품절이나 중복으로 받은 409는 서버 장애가 아니라 정상적인 발급 거절이다.
http.setResponseCallback(
  http.expectedStatuses(202, 409)
);

export const options = {
  scenarios: {
    smoke: {
      // 본 테스트 전에 API 주소와 응답 형식만 빠르게 확인한다.
      // 각 VU가 한 번씩만 요청하므로 기본값 기준 총 10건이 전송된다.
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_reqs: [`count==${VUS}`],
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1'],
    smoke_system_error_5xx: ['count==0'],
    smoke_other_error: ['count==0'],
  },
};

export default function () {
  const memberIdStart = Number(__ENV.MEMBER_ID_START || '1');
  // VU마다 다른 회원 ID를 사용해서 스모크 테스트 자체가 중복 요청이 되지 않게 한다.
  const memberId = memberIdStart + __VU - 1;

  const url = `${TARGET}/api/coupons/${COUPON_ID}/issues`;
  const payload = JSON.stringify({ memberId });

  const res = http.post(url, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'smoke-issue' },
    timeout: '5s',
  });

  check(res, {
    '응답이 202 또는 409이다': (r) => r.status === 202 || r.status === 409,
    '서버 오류가 없다': (r) => r.status < 500,
  });

  if (res.status === 202) {
    success202.add(1);
    return;
  }

  if (res.status >= 500) {
    systemError5xx.add(1);
    return;
  }

  if (res.status !== 409) {
    otherError.add(1);
    return;
  }

  try {
    // 공통 ApiResponse의 error.code를 기준으로 거절 사유를 나눠서 집계한다.
    const errorCode = res.json('error.code');
    if (errorCode === 'DUPLICATE') {
      duplicate409.add(1);
    } else if (errorCode === 'SOLD_OUT') {
      soldOut409.add(1);
    } else {
      otherError.add(1);
    }
  } catch (e) {
    otherError.add(1);
  }
}
