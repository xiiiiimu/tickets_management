// Opt-in real HTTP attempt workload. No fixture creation, flag changes or fast consumer.
// Run through run-steady-state.ps1; credentials are read from the local fixture, never logged.
import http from 'k6/http';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Counter, Trend} from 'k6/metrics';

const rate = Number(__ENV.ATTEMPT_RATE || 100);
const seconds = Number(__ENV.ATTEMPT_SECONDS || 300);
const warmup = Number(__ENV.ATTEMPT_WARMUP_SECONDS || 10);
const vus = Number(__ENV.ATTEMPT_VUS || 128);
if (!Number.isInteger(rate) || rate < 1 || rate > 10000 ||
    !Number.isInteger(seconds) || seconds < 300 || seconds > 600 ||
    !Number.isInteger(warmup) || warmup < 1 || warmup > 60 ||
    !Number.isInteger(vus) || vus < 1 || vus > 512) throw new Error('Invalid bounded workload settings');
if (__ENV.ATTEMPT_EXECUTE !== 'isolated-schema-confirmed') throw new Error('Explicit execution acknowledgement required');
if (!/^http:\/\/(127\.0\.0\.1|localhost):[0-9]+\/api\/tickets\/reservations$/.test(__ENV.ATTEMPT_URL || ''))
  throw new Error('Only an explicitly selected localhost attempt endpoint is supported');
const users = new SharedArray('owned-isolated-attempt-fixtures', () => {
  const fixture = JSON.parse(open(__ENV.ATTEMPT_FIXTURES));
  if (fixture.isolated !== true || !/^credit_risk_controlled_[a-z0-9_]+$/.test(fixture.schema || ''))
    throw new Error('Explicit isolated fixture metadata required');
  // One unique identity per iteration avoids active_owner aliases and accumulated purchase limits.
  if (!Array.isArray(fixture.requests) || fixture.requests.length < rate * (warmup + seconds))
    throw new Error('Not enough unique prepared identities; do not reuse users silently');
  const identities = new Set();
  const skus = new Set();
  for (const item of fixture.requests) {
    if (!Number.isSafeInteger(item.userId) || item.userId < 1 || !item.token || !item.deviceId ||
        !item.eventSessionId || !item.ticketSkuId || identities.has(item.userId))
      throw new Error('Invalid or repeated fixture identity');
    identities.add(item.userId); skus.add(item.ticketSkuId);
  }
  if (__ENV.ATTEMPT_SKU_MODE === 'single' && skus.size !== 1) throw new Error('Single SKU fixture mismatch');
  if (__ENV.ATTEMPT_SKU_MODE === 'multi' && skus.size < 2) throw new Error('Multi SKU fixture mismatch');
  return fixture.requests;
});

const started = new Counter('attempt_post_started');
const completed = new Counter('attempt_post_completed');
const accepted = new Counter('attempt_post_202');
const registered = new Counter('attempt_new_registration');
const rejected = new Counter('attempt_post_429');
const unavailable = new Counter('attempt_post_503');
const unexpected = new Counter('attempt_post_other');
const transport = new Counter('attempt_post_transport_error');
const duration = new Trend('attempt_post_ms', true);

export const options = {
  discardResponseBodies: true,
  noConnectionReuse: false,
  noVUConnectionReuse: false,
  scenarios: {
    warmup: {executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: warmup + 's',
      preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '3s', tags: {phase: 'warmup'}},
    measured: {executor: 'constant-arrival-rate', rate, timeUnit: '1s', startTime: (warmup + 3) + 's',
      duration: seconds + 's', preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '3s', tags: {phase: 'measured'}},
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  systemTags: ['status', 'method', 'scenario', 'name'],
};
// Materialize measured-only submetrics even when there are zero successes/rejections.
for (const name of ['attempt_post_started','attempt_post_completed','attempt_post_202','attempt_new_registration',
  'attempt_post_429','attempt_post_503','attempt_post_other','attempt_post_transport_error','attempt_post_ms',
  'http_reqs','dropped_iterations']) options.thresholds = {...options.thresholds, [name + '{phase:measured}']: []};

http.setResponseCallback(http.expectedStatuses(202, 429, 503));
export default function () {
  const index = exec.scenario.iterationInTest + (exec.scenario.name === 'measured' ? rate * warmup : 0);
  const fixture = users[index];
  if (!fixture) exec.test.abort('Fixture exhausted; no identity reuse allowed');
  const phase = exec.scenario.name === 'measured' ? 'measured' : 'warmup';
  const tags = {phase};
  const requestId = __ENV.ATTEMPT_RUN_ID + '-' + index;
  started.add(1, tags);
  const response = http.post(__ENV.ATTEMPT_URL, JSON.stringify({requestId,
    userId: fixture.userId, eventSessionId: fixture.eventSessionId, ticketSkuId: fixture.ticketSkuId,
    quantity: fixture.quantity || 1, deviceId: fixture.deviceId}), {
      headers: {'Content-Type': 'application/json', authorization: fixture.token},
      redirects: 0, timeout: '3s', responseType: 'text', tags: {...tags, name: 'attempt_POST'},
    });
  duration.add(response.timings.duration, tags);
  if (response.error_code) transport.add(1, tags);
  if (response.status > 0) completed.add(1, tags);
  if (response.status === 202) {
    accepted.add(1, tags);
    let data;
    try { data = response.json('data'); } catch (_) { data = null; }
    if (!data || data.reliableAccepted !== true || !data.attemptId)
      exec.test.abort('202 was not a reliable attempt receipt');
    if (data.newSubmission === true) registered.add(1, tags);
  } else if (response.status === 429) rejected.add(1, tags);
  else if (response.status === 503) unavailable.add(1, tags);
  else unexpected.add(1, tags);
}

export function handleSummary(data) {
  const count = name => {
    const metric = data.metrics[name + '{phase:measured}'];
    return metric ? metric.values.count : 0;
  };
  const result = {target_rate: rate, measured_seconds: seconds, target_iterations: rate * seconds,
    iterations_started: count('attempt_post_started'), responses_completed: count('attempt_post_completed'),
    http_requests_sent: count('http_reqs'), server_received: null,
    post_http_qps: count('attempt_post_completed') / seconds,
    accepted_202_qps: count('attempt_post_202') / seconds,
    new_registration_tps: count('attempt_new_registration') / seconds,
    status_429: count('attempt_post_429'), status_503: count('attempt_post_503'),
    other_status: count('attempt_post_other'), transport_errors: count('attempt_post_transport_error'),
    dropped_iterations: count('dropped_iterations'),
    note: 'Counters include responses during the bounded 3s tail, divided by the configured sending window. HELD and QUEUE_TIMEOUT require server evidence; 202 is not HELD.',
    k6: data};
  return {[__ENV.ATTEMPT_SUMMARY]: JSON.stringify(result, null, 2)};
}
