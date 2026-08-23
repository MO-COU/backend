import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const issueIds = JSON.parse(open(__ENV.ISSUE_IDS_PATH));
const target = __ENV.TARGET;
const rate = Number(__ENV.ARRIVAL_RATE || '333');
const success = new Counter('use_success');
const unexpected = new Counter('unexpected_response');

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: __ENV.MAX_DURATION || '120s',
      preAllocatedVUs: Math.max(100, rate), maxVUs: Math.max(1000, rate * 4), gracefulStop: '10s',
    },
  },
  thresholds: { dropped_iterations: ['count==0'], http_req_failed: ['rate==0'], unexpected_response: ['count==0'] },
};

export default function () {
  const issueId = issueIds[exec.scenario.iterationInTest];
  if (!issueId) { unexpected.add(1); return; }
  const response = http.post(`${target}/api/coupon-issues/${issueId}/use`, null, {
    headers: { 'Idempotency-Key': `sustained-${issueId}` }, timeout: '5s',
  });
  if (response.status === 200) success.add(1); else unexpected.add(1);
}
