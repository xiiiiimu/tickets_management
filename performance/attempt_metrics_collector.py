"""Server-side metrics collector for attempt throughput runs (read-only, stdlib only).

Scrapes the application's /actuator/prometheus endpoint at a fixed interval while a load test runs, keeps every
raw sample, and writes a summary of what the server itself saw during the window:

* counter deltas: registrations, 429 classification (DB_BUSY / CAPACITY_REJECT / GATE_REJECT), gate decisions,
  claim grants (first / recovery), queue timeouts, batch wait timeouts, failures by code
* per-operation transaction latency (register, register_batch, claim, claim_batch, prepare) from the
  attempt.transaction.duration SLO histogram, computed from bucket DELTAS of the window (approximate quantiles)
* peaks of Hikari active/pending connections, sale pool activity and registrar queue depth

It never writes to the server, database or Redis. Pair it with k6 (client view) — 202 counts are not HELD counts;
HELD/QUEUE_TIMEOUT come from the server counters here or from the database afterwards.

Usage:
  python performance/attempt_metrics_collector.py --url http://127.0.0.1:8082/actuator/prometheus --seconds 330 --interval 1
"""
import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+([-+0-9.eEInfNa]+)(\s+\d+)?$')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')

COUNTERS = [
    'attempt_registered_total', 'attempt_receipt_committed_total', 'attempt_admission_rejected_total',
    'attempt_admission_rejected_internal_total', 'attempt_gate_decision_total', 'attempt_gate_reconcile_total',
    'attempt_gate_reconcile_failed_total', 'attempt_claim_granted_total', 'attempt_queue_timeout_total',
    'attempt_register_batch_wait_timeout_total', 'attempt_worker_rejected_total', 'attempt_ordered_deferred_total',
    'reservation_v2_failed_total',
]
GAUGES = ['hikaricp_connections_active', 'hikaricp_connections_pending', 'threadpool_active',
          'attempt_register_batch_pending', 'attempt_worker_ready']
HISTOGRAM = 'attempt_transaction_duration_seconds_bucket'


def parse(text):
    samples = []
    for raw in text.splitlines():
        if not raw or raw.startswith('#'):
            continue
        m = LINE.match(raw.strip())
        if not m:
            continue
        labels = dict(LABEL.findall(m.group(3) or ''))
        try:
            value = float(m.group(4))
        except ValueError:
            continue
        samples.append((m.group(1), labels, value))
    return samples


def key(name, labels, drop=()):
    return name + '{' + ','.join(f'{k}={v}' for k, v in sorted(labels.items()) if k not in drop) + '}'


def scrape(url, timeout):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode('utf-8', 'replace')


def quantile(buckets, q):
    """buckets: sorted [(le_seconds, cumulative_delta)] including +Inf. Returns ms (upper bound of the bucket)."""
    total = buckets[-1][1] if buckets else 0
    if total <= 0:
        return None
    target = q * total
    for le, cumulative in buckets:
        if cumulative >= target:
            return None if le == float('inf') else round(le * 1000, 3)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', required=True, help='http://127.0.0.1:<port>/actuator/prometheus')
    parser.add_argument('--seconds', type=int, required=True, help='collection window, 1..7200')
    parser.add_argument('--interval', type=float, default=1.0, help='scrape interval seconds, 0.2..30')
    parser.add_argument('--out', type=Path, help='output directory (default performance/results/attempt-metrics-<time>)')
    args = parser.parse_args()
    if not re.match(r'^http://(127\.0\.0\.1|localhost):\d+/', args.url):
        parser.error('only a localhost server is supported')
    if not 1 <= args.seconds <= 7200 or not 0.2 <= args.interval <= 30:
        parser.error('seconds 1..7200, interval 0.2..30')
    out = args.out or ROOT / 'performance/results' / ('attempt-metrics-' + time.strftime('%Y%m%d_%H%M%S'))
    out.mkdir(parents=True, exist_ok=False)
    raw = (out / 'samples.jsonl').open('w', encoding='utf-8')
    first = last = None
    peaks, errors, scrapes = {}, 0, 0
    deadline = time.monotonic() + args.seconds
    try:
        while True:
            started = time.monotonic()
            try:
                samples = parse(scrape(args.url, timeout=max(1.0, args.interval)))
                scrapes += 1
                record = {'t': time.time(), 'counters': {}, 'gauges': {}, 'histogram': {}}
                for name, labels, value in samples:
                    if name in COUNTERS:
                        record['counters'][key(name, labels)] = value
                    elif name in GAUGES:
                        k = key(name, labels)
                        record['gauges'][k] = value
                        peaks[k] = max(peaks.get(k, value), value)
                    elif name == HISTOGRAM:
                        record['histogram'][key(name, labels)] = value
                raw.write(json.dumps(record) + '\n')
                raw.flush()
                first = first or record
                last = record
            except Exception as error:  # keep collecting; a missed scrape is recorded, not fatal
                errors += 1
                raw.write(json.dumps({'t': time.time(), 'error': type(error).__name__ + ': ' + str(error)[:200]}) + '\n')
            if time.monotonic() >= deadline:
                break
            time.sleep(max(0.0, args.interval - (time.monotonic() - started)))
    finally:
        raw.close()
    summary = {'url': args.url, 'seconds': args.seconds, 'interval': args.interval, 'scrapes': scrapes,
               'scrape_errors': errors, 'note': 'Server-side deltas over the window. Quantiles are SLO-bucket upper bounds (approximate).'}
    if first and last:
        window = max(1e-9, last['t'] - first['t'])
        summary['window_seconds'] = round(window, 3)
        deltas = {k: last['counters'][k] - first['counters'].get(k, 0.0) for k in last['counters']}
        summary['counter_deltas'] = {k: v for k, v in sorted(deltas.items()) if v}
        summary['rates_per_second'] = {k: round(v / window, 3) for k, v in summary['counter_deltas'].items()}
        # Transaction latency per operation/outcome/phase from bucket deltas of THIS window.
        grouped = {}
        for k, v in last['histogram'].items():
            labels = dict(part.split('=', 1) for part in k[k.index('{') + 1:-1].split(',') if '=' in part)
            le = labels.pop('le', None)
            if le is None:
                continue
            group = '/'.join(labels.get(x, '?') for x in ('operation', 'outcome', 'phase'))
            bound = float('inf') if le in ('+Inf', 'Inf') else float(le)
            grouped.setdefault(group, []).append((bound, v - first['histogram'].get(k, 0.0)))
        latency = {}
        for group, buckets in sorted(grouped.items()):
            buckets.sort()
            count = buckets[-1][1] if buckets else 0
            if count > 0:
                latency[group] = {'count': count, 'p50_ms': quantile(buckets, .5), 'p95_ms': quantile(buckets, .95), 'p99_ms': quantile(buckets, .99)}
        summary['transaction_latency'] = latency
        summary['gauge_peaks'] = dict(sorted(peaks.items()))
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(out)


if __name__ == '__main__':
    main()
