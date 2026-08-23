import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const issueIds = JSON.parse(open(__ENV.ISSUE_IDS_PATH));
const target = __ENV.TARGET;
const used = new Counter('used_success');
const expired = new Counter('coupon_expired');
const unexpected = new Counter('unexpected_response');

http.setResponseCallback(http.expectedStatuses(200, 410));

export const options = {
  scenarios: {
    race: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  thresholds: { dropped_iterations: ['count==0'], http_req_failed: ['rate==0'], unexpected_response: ['count==0'] },
};

export default function () {
  const issueId = issueIds[exec.scenario.iterationInTest];
  if (!issueId) { unexpected.add(1); return; }
  const response = http.post(`${target}/api/coupon-issues/${issueId}/use`, null, {
    headers: { 'Idempotency-Key': `race-${issueId}` },
    timeout: '5s',
  });
  if (response.status === 200) used.add(1);
  else if (response.status === 410 && response.json('error.code') === 'COUPON_EXPIRED') expired.add(1);
  else unexpected.add(1);
}
