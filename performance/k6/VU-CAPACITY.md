# Bounded 5000/s VU experiment (tiny HTTP only)

```powershell
cd E:\credit_risk
.\performance\k6\run-vu-capacity.ps1
```

Uses the unchanged tiny-http.cjs service, loopback18089, GET with no body, fixed200/OK response and keep-alive. Only the existing arrival.js VU budget is parameterized (`VUS`); default remains128 for older scripts. Supported budgets are128/256/512/768. This entry point only starts the tiny Node fixture and k6; it has no branch that launches the ticket ingress or performs Docker operations, even on success.

Fixed arrival target5000/s for5seconds; consecutive budgets256,512,768, with maxVUs equal to preAllocatedVUs. The same Node process serves authorized stages; each k6 process is new. No service settings are changed. All previous uncommitted work and historical results remain intact.

Pass: completed response QPS >=4750, dropped_iterations/25000 <=1%, exact successful-count reconciliation and zero transport/non200 errors or resource guard. Stop immediately after pass, errors, discrepancies or inadequate resource headroom. Stop further growth if gain <=3%, or HTTP P95/P99 worsens by >=50% compared with the previous stage. These are explicit experiment policies interpreting stagnation/significant deterioration, not application tuning. No1024+ stage exists.

During measurement, the shared runner stops after three samples of host freeRAM<256MiB, clientRSS>768MiB or hostCPU>=97%. No next stage if freeRAM<512MiB or average hostCPU>=90%. Every child has a finite timeout. The owned tiny process is stopped in finally. No existing process/container is stopped.

Each stage retains raw k6-summary.json, console.log,500ms resource-samples.json, exact configuration and derived result.json. The experiment also saves source hashes, versions, host snapshot, production-before hash map, plan, comparison and cleanup. CPU values are process core equivalents over an interval including k6 initialization/shutdown; sampled RSS is not JVM/Go heap size.

Counters distinguish target25000, started iterations (`request_attempts`), completed iterations (`iterations`), k6 `http_reqs` attempts, independently confirmed server receives/finishes, and numeric HTTP responses. `http_reqs` can include failed connection attempts, so it must not be called actual wire transmission when connections fail. k6 arrival scheduling counts may differ slightly from rate*duration at the boundary; raw counts are preserved without forcing equality. Dropped iterations are not server rejections. Response QPS includes the short in-flight completion tail. The final report's main P95/P99 describe iteration_duration; http_req_duration is separately retained.

Report comparisons are short diagnostic samples, not stable capacity or component attribution. Connection refusals and VU exhaustion are evidence; they do not alone identify listen-backlog saturation, Node event-loop limits, k6 scheduling or Windows TCP behavior. A setup run where the execution environment denies loopback access is invalid for performance and retained separately. It is rerun only with authorized local-socket access, without changing the fixture or budgets.
