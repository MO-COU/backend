import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

// 커스텀 지표 정의
const success202 = new Counter('issue_success_202');
const duplicate409 = new Counter('issue_duplicate_409');
const soldOut409 = new Counter('issue_sold_out_409');
const systemError5xx = new Counter('issue_system_error_5xx');
const otherError = new Counter('issue_other_error');

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '301';
const RAMP_UP = __ENV.RAMP_UP || '60s';
const VUS = Number(__ENV.VUS || '20000');
const EXPECTED_STOCK = Number(__ENV.EXPECTED_STOCK || '10000');

if (!Number.isInteger(VUS) || VUS <= 0) {
  throw new Error(`VUS는 양의 정수여야 합니다: ${__ENV.VUS}`);
}

if (!Number.isInteger(EXPECTED_STOCK) || EXPECTED_STOCK < 0) {
  throw new Error(
    `EXPECTED_STOCK은 0 이상의 정수여야 합니다: ${__ENV.EXPECTED_STOCK}`
  );
}

const expectedSuccess = Math.min(VUS, EXPECTED_STOCK);
const expectedSoldOut = Math.max(VUS - EXPECTED_STOCK, 0);
let requested = false;

// 202는 발급 예약 성공이고 409는 품절/중복에 따른 정상적인 발급 거절이다.
// 409를 예상 응답으로 등록하지 않으면 k6가 품절 요청까지 서버 장애로 집계한다.
http.setResponseCallback(
  http.expectedStatuses(202, 409)
);

export const options = {
  scenarios: {
    rush: {
      // 중복 없는 사용자가 0명에서 VUS명까지 RAMP_UP 동안 증가한다.
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: VUS },
        // 마지막에 도착한 사용자도 한 번은 요청할 시간을 둔다.
        { duration: '1s', target: VUS },
      ],
      gracefulRampDown: '30s',
    },
  },

  thresholds: {
    // VU마다 첫 요청만 보내므로 총 요청 수는 VUS와 같아야 한다.
    http_reqs: [`count==${VUS}`],
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1'],
    issue_success_202: [`count==${expectedSuccess}`],
    issue_sold_out_409: [`count==${expectedSoldOut}`],
    issue_duplicate_409: ['count==0'],
    issue_system_error_5xx: ['count==0'],
    issue_other_error: ['count==0'],
  },
};

export default function () {
  // ramping-vus는 함수를 반복하므로 사용자마다 발급 요청은 한 번만 보낸다.
  if (requested) {
    sleep(1);
    return;
  }
  requested = true;

  const memberIdStart = Number(__ENV.MEMBER_ID_START || '1');
  const memberId = memberIdStart + exec.vu.idInTest - 1;

  const url = `${TARGET}/api/coupons/${COUPON_ID}/issues`;
  const payload = JSON.stringify({ memberId });

  const res = http.post(url, payload, {
    headers: {
      'Content-Type': 'application/json',
    },
    tags: {
      name: 'flash-sale-issue',
    },
    timeout: __ENV.REQUEST_TIMEOUT || '10s',
  });

  check(res, {
    '응답이 202 또는 409이다': (r) =>
      r.status === 202 || r.status === 409,
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
    // 거절 사유를 나눠서 센다.
    const errorCode = res.json('error.code');

    if (errorCode === 'DUPLICATE') {
      duplicate409.add(1);
    } else if (errorCode === 'SOLD_OUT') {
      soldOut409.add(1);
    } else {
      otherError.add(1);
    }
  } catch (error) {
    otherError.add(1);
  }
}
