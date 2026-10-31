# Bounded local sender validation

```powershell
cd E:\credit_risk
.\performance\k6\run.ps1
```

Installed official Grafana k6 v2.3.0 in `.tools/k6-v2.3.0-windows-amd64`. No PATH/system environment changes. The official GitHub release API metadata is retained in `.tools/k6-release.json`; the downloaded archive was verified against both the API SHA256 digest and the official checksums file. Sources:

- https://grafana.com/docs/k6/latest/set-up/install-k6/
- https://github.com/grafana/k6/releases/tag/v2.3.0
- https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-checksums.txt

The first stage starts only the independent Node HTTP fixture on loopback18089. Every request receives200 and a fixed two-byte OK body. Request handling reads no files or external systems; counters are in memory. Out-of-band stdin/stdout snapshots reconcile server-received and finished responses without adding HTTP control requests. No Redis, MySQL or Spring process starts in this stage.

`arrival.js` uses constant-arrival-rate at1000/2000/5000 iterations per second,5-second arrival windows, HTTP keep-alive,128 preallocated VUs and a fixed128-VU ceiling. No end-of-iteration sleep, redirects, retries, graphical listener, cloud output or full response logging is used. Each iteration invokes exactly one HTTP request. A maximum3-second grace permits already-started work to finish; timeout is2seconds. VU allocation is deliberately finite. Exhaustion proves this configuration is underdriven; it does not establish the machine or service's ultimate capacity. When the gate fails the runner stops instead of increasing VUs or starting the ingress fixture.

Stage1 gate: the5000/s case must achieve at least4900 completed responses/s, no more than125 dropped iterations, zero transport/non200 errors, and reconcile server-received count with successful responses. These are explicit diagnostic acceptance thresholds, not k6 defaults. An iteration at the exact5s scheduling boundary can make scheduled counts differ from rate*5 by one. dropped_iterations are requests not started, never server rejections.

`request_attempts` counts invocation of the HTTP call, not packet-level transmission. Numeric HTTP responses plus the tiny service counter independently prove receipt/completion. QPS denominator is max(5s, last-completion-offset), including the short response tail; nominal count/5 is separately retained. P95/P99 in the report use standard k6 http_req_duration. Custom http_call_ms also retains caller elapsed time. Raw k6 aggregated summaries, console, script/configuration hashes and500ms process/host resource samples are retained without costly per-request output. Process CPU core equivalents include the short k6 initialization/shutdown interval; sampled memory is RSS, not heap allocation.

Only after stage1 passes may the runner load existing AuthModeIngress: modeC and modeA at1000/2000/5000, same arrival model and VU cap. The production1000/s per-instance ingress bucket,4096-entry state bound, queue, consumers and all other settings remain unchanged. Each202 is verified as newSubmission and reconciled to actual fast-consumer completion.429 is separately counted. Insufficient VUs at high authentication latency must be reported as underdriven load, not server capacity. This branch uses no real risk/stock/SQL/payment. The existing disposable token preparation and exact-key cleanup are reused.

Every child process has a finite timeout and finally cleanup. Resource guard stops after three samples with host free RAM below256MiB or k6 RSS above768MiB. The runner never starts/restarts/stops existing containers. Production sources are hashed before/after. Only owned processes and exact keys from this invocation are cleaned. A failed gate is a completed diagnosis and exits without stage2; see stage1-gate.json and cleanup.json.
