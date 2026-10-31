import http from 'k6/http';
import exec from 'k6/execution';
import {Counter, Trend} from 'k6/metrics';

const attempts = new Counter('request_attempts');
const completed = new Counter('response_completed');
const ok = new Counter('status_200');
const accepted = new Counter('submitted_202');
const rejected = new Counter('status_429');
const unauthorized = new Counter('status_401');
const other = new Counter('status_other');
const errors = new Counter('transport_errors');
const timeouts = new Counter('request_timeouts');
const lastCompletion = new Trend('completion_offset_ms');
const callTime = new Trend('http_call_ms');
const ingress = __ENV.KIND === 'ingress';
const vus = Number(__ENV.VUS || 128);
if (![128, 256, 512, 768].includes(vus)) throw new Error('Unsupported diagnostic VU budget');

export const options = {
  discardResponseBodies: true,
  noConnectionReuse: false,
  noVUConnectionReuse: false,
  scenarios: {load: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE), timeUnit: '1s',
    duration: '5s', preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '3s',
  }},
  summaryTrendStats: ['avg','min','med','max','p(95)','p(99)'],
};

http.setResponseCallback(http.expectedStatuses(200, 202, 429));
export default function () {
  const seq = exec.scenario.iterationInTest;
  attempts.add(1);
  const begin = Date.now();
  let response;
  if (!ingress) {
    response = http.get(__ENV.URL, {redirects: 0, timeout: '2s'});
  } else {
    const index = seq % 128;
    const rid = __ENV.RUN_ID + '-' + seq;
    response = http.post(__ENV.URL, JSON.stringify({requestId: rid, userId: 100000 + index,
      eventSessionId: 'fast-session', ticketSkuId: 'fast-sku', quantity: 1,
      deviceId: 'fast-device-' + index}), {redirects: 0, timeout: '2s', responseType: 'text',
        headers: {'Content-Type': 'application/json', authorization: __ENV.TOKEN_PREFIX + index,
          'X-Fast-Trace': rid}});
  }
  callTime.add(Date.now() - begin);
  lastCompletion.add(Date.now() - exec.scenario.startTime);
  if (response.error_code) {
    if (__ENV.CONNECTION_DIAG === '1') console.warn(JSON.stringify({diagnostic: 'connection_failure',
      begin_epoch_ms: begin, end_epoch_ms: Date.now(), vu: exec.vu.idInTest,
      code: response.error_code, error: response.error, status: response.status}));
    errors.add(1); if (response.error_code === 1050) timeouts.add(1);
  }
  if (response.status > 0) completed.add(1);
  if (response.status === 200) ok.add(1);
  else if (response.status === 202) {
    let received = false;
    try {received = response.json('data.newSubmission') === true;} catch (_) {}
    if (received) accepted.add(1); else other.add(1);
  } else if (response.status === 429) rejected.add(1);
  else if (response.status === 401) unauthorized.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  return {[__ENV.SUMMARY_PATH]: JSON.stringify(data, null, 2)};
}
