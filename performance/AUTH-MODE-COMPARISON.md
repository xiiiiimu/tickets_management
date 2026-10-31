# Test-only authentication cost comparison

```powershell
cd E:\credit_risk
.\performance\run-auth-mode-comparison.ps1
```

Requires the existing test launcher/dependency classpath, JDK21 and already-running `credit-risk-v2-redis`. This script never starts/restarts containers. No Maven build, real risk, stock, SQL, orders or payment execute.

`AuthModeIngress.java` lives in performance/, outside every Maven source directory. It compiles only into `.tmp/auth-mode-classes`. The production JAR is checked for absence of the fixture. The ordinary Spring Boot main class and default quick-test launcher do not import this assembly; no production property enables it. The independent main also rejects startup unless the explicit isolated flag, loopback server and dedicated Redis port are present. The existing fixture additionally requires a fresh claimed Redis logical database.

Only POST `/api/tickets/reservations` changes authentication mode. Mode A delegates to the original production RefreshTokenInterceptor. Mode B preserves the original hash-to-UserDTO conversion and skips expiration. Mode C accepts only the current fixture's 128 known token strings and constructs the matching test user. Missing/invalid credentials still fail the unchanged LoginInterceptor; user/body mismatch and parameter validation remain unchanged. Other URLs, including administrative mode control, retain original Redis authentication and admin checks. B/C are semantic diagnostic bypasses and must never be represented as deployable authentication.

All modes share one server process, JVM settings (128m/384m, SerialGC), original Controller/validation, bounded ingress/workers, response format and actual fast-consuming fixture. No worker, queue, limiter or Tomcat property is changed. Existing limiter is 1000/s with burst 1000; state storage capacity 4096. Consequently successful admission at 5000/s is not a supported configuration in this test. A high response QPS consisting of 429 is never reported as successful submission QPS.

Every fixture token starts with the existing 30-minute TTL; TTL validity is checked before every window. A slides expiration; B/C do not. This changes only disposable test-token initialization, not production expiry configuration. No per-request production cache is introduced.

Smoke verifies A=one HGETALL+one PEXPIRE, B=one HGETALL and no PEXPIRE, C=neither; it also verifies missing/invalid token, user mismatch, invalid quantity and duplicate submission behavior. Measurement repeats the expected request-scoped command-count check and verifies submitted count equals consumer count increment. Diagnostic admin calls use Redis but are not counted as POST authentication commands. Any mismatch aborts immediately and preserves raw artifacts.

Measured order at concurrency64, target700/s, is A/B/C/C/B/A; every load window is 5 seconds, with a 1-second low-load warmup before it. The existing Python client uses a bounded scheduled producer, keep-alive and 128 identities; it skips late/full producer slots. Actual sends, completions, remaining in-flight at the deadline, client omissions and server rejections are distinct counters. QPS includes the short completion tail. Per-round percentiles and raw timings are saved; a median of per-round percentiles is not a pooled percentile. Two samples per mode cannot establish stable capacity.

Higher targets are optional and guarded: C must send at least98% of its700/s target and a simple CPU-headroom projection must leave room for2000/s in this single-process client. This projection is a stop heuristic, not proof of sender capacity. Otherwise higher tiers are omitted explicitly. If2000 is attempted, only one A/B/C set is used, with that sample limitation disclosed.5000 is not attempted without demonstrated sender capacity; unchanged limiter/state limits also constrain useful admission throughput.

Outputs in `performance/results/auth-mode-*` include smoke results, per-window request timings without response bodies, token TTL validity, command timings/counts, client/JVM resources and GC, Tomcat activity, ingress counters and exact cleanup verification. Existing source changes are preserved; production source hashes must remain identical. Only this invocation's JVM and exact disposable Redis keys are cleaned. Failed setup runs remain available and are excluded from performance conclusions.
