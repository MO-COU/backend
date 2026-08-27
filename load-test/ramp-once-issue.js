// V4: 20,000 VU를 60초 동안 늘려 한 번씩 요청함.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const success202 = new Counter('issue_success_202');
const duplicate409 = new Counter('issue_duplicate_409');
const soldOut409 = new Counter('issue_sold_out_409');
const systemError5xx = new Counter('issue_system_error_5xx');
const otherError = new Counter('issue_other_error');
const usersStarted = new Counter('issue_users_started');

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '301';
const RAMP_UP = __ENV.RAMP_UP || '60s';
const HOLD = __ENV.HOLD || '10s';
const VUS = Number(__ENV.VUS || '20000');
const EXPECTED_STOCK = Number(__ENV.EXPECTED_STOCK || '10000');
let requested = false;

if (!Number.isInteger(VUS) || VUS <= 0) throw new Error('VUS는 양의 정수여야 합니다.');
if (!Number.isInteger(EXPECTED_STOCK) || EXPECTED_STOCK < 0) throw new Error('EXPECTED_STOCK을 확인해주세요.');

const expectedSuccess = Math.min(VUS, EXPECTED_STOCK);
const expectedSoldOut = Math.max(VUS - EXPECTED_STOCK, 0);

function oneSecondRampStages(duration) {
  const matched = /^(\d+)s$/.exec(duration);
  if (!matched || Number(matched[1]) <= 0) {
    throw new Error('RAMP_UP은 60s처럼 초 단위 양수로 입력해야 합니다.');
  }
  const totalSeconds = Number(matched[1]);
  return Array.from({ length: totalSeconds }, (_, index) => ({
    duration: '1s',
    target: Math.round((VUS * (index + 1)) / totalSeconds),
  }));
}

http.setResponseCallback(http.expectedStatuses(202, 409));

export const options = {
  scenarios: {
    rampOnce: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [...oneSecondRampStages(RAMP_UP), { duration: HOLD, target: VUS }],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    issue_users_started: [`count==${VUS}`],
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

function recordResponse(res) {
  if (res.status === 202) success202.add(1);
  else if (res.status >= 500) systemError5xx.add(1);
  else if (res.status !== 409) otherError.add(1);
  else {
    try {
      const errorCode = res.json('error.code');
      if (errorCode === 'DUPLICATE') duplicate409.add(1);
      else if (errorCode === 'SOLD_OUT') soldOut409.add(1);
      else otherError.add(1);
    } catch (error) {
      otherError.add(1);
    }
  }
}

export default function () {
  if (requested) {
    sleep(1);
    return;
  }
  requested = true;
  usersStarted.add(1);

  const memberId = Number(__ENV.MEMBER_ID_START || '1') + exec.vu.idInTest - 1;
  const res = http.post(
    `${TARGET}/api/coupons/${COUPON_ID}/issues`,
    JSON.stringify({ memberId }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'flash-sale-ramp-once' },
      timeout: __ENV.REQUEST_TIMEOUT || '10s',
    }
  );

  check(res, {
    '응답이 202 또는 409이다': (r) => r.status === 202 || r.status === 409,
    '서버 오류가 없다': (r) => r.status < 500,
  });
  recordResponse(res);
}

export function handleSummary(data) {
  const count = (name) => Math.trunc(data.metrics[name]?.values?.count || 0);
  const result = {
    requestedCount: count('http_reqs'),
    issuedCount: count('issue_success_202'),
    soldOutCount: count('issue_sold_out_409'),
    duplicateCount: count('issue_duplicate_409'),
    errorCount: count('issue_system_error_5xx') + count('issue_other_error'),
    p95Ms: Math.round(data.metrics.http_req_duration?.values?.['p(95)'] || 0),
  };
  return {
    stdout: `MOCOU_RESULT=${JSON.stringify(result)}\n`,
    [__ENV.SUMMARY_FILE || 'summary.json']: JSON.stringify(result),
  };
}
