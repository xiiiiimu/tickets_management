# Authorized WSL2 client / Windows gateway listener test

```powershell
cd E:\credit_risk
.\performance\k6\run-wsl-gateway.ps1
```

The entry point re-queries Ubuntu's default route and source address. It aborts if the gateway no longer equals the explicitly authorized172.30.192.1, verifies Windows owns that IP using GetIpAddrTable, requires no existing18089 listener, and checks socket binding is possible. It starts the unchanged tiny-http.cjs with `node --require bind-wsl-gateway.cjs`; this startup-only adapter substitutes the host argument for port18089 while forwarding all other listen arguments. The original handler, source hash, port, backlog, response and keep-alive remain unchanged. GetExtendedTcpTable confirms the actual listener address and owned PID.

Ten direct-IP requests from Ubuntu must all return200/OK and increment the Windows service receive counter by exactly10. No proxy is used. No firewall rule was needed in the executed experiment. The runner does not write firewall policy or change Windows/WSL networking. If connectivity fails, it stops instead of editing persistent policy.

The same arrival.js runs natively through Linux k6v2.3.0. Planned stages:1000/s128VU,3000/s256VU,5000/s256VU,5000/s512VU only if needed. Each arrival window lasts5seconds; keep-alive,2-second request timeout and3-second grace remain identical. Pass is>=4750 completed/s,<=1% dropped, exact count reconciliation and near-zero errors. No stage is repeated after success; a passing5000 tier ends testing.

Resource stabilization is checked before every stage. The final diagnostic entry budget requires Windows available RAM>=384MiB, WSL available RAM>=512MiB, and host CPU<85% for three samples. This budget accounts for already-assigned free/reclaimable memory inside WSL; it changes no OS/VM resource setting. During load both sides retain256MiB low-memory hard-stop guards; k6 RSS>768MiB also stops. Earlier startup-only attempts used a512MiB Windows entry reserve and emitted no load; those directories are retained and excluded from reported performance.

The runner stops on connection errors, timeouts, non200, count mismatch, resource guards, or sharp latency deterioration: HTTP P95>100ms and>2x prior stage, or P99>250ms and>2x prior. Such guards may stop before a5000 tier; that is not a measured failure at5000 and must not be reported as capacity.

wsl_load.py owns exactly one Linux child and samples /proc CPU/RSS/memory, with finite timeouts and finally cleanup. Its CPU report is Linux k6 process CPU, not Windows wsl.exe usage. Windows independently samples Node and host resources. Each result has target/started/HTTP metrics/server received/finished/completed counts, response and iteration percentiles, drops, errors, connection reuse, resources and configuration. No response body or per-request output is persisted. QPS includes the short in-flight completion tail. k6 boundary scheduling can differ slightly from target rate*5; raw values are preserved.

Cleanup terminates only owned test children and Node, preserves user WSL processes and all Docker resources, verifies listener removal and source hashes. There is no ingress/Java/risk/database/payment test branch. A later run requires manual invocation.
