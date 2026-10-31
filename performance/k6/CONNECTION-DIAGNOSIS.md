# TCP refusal diagnosis

The original134 failures occurred in ConnectEx before HTTP transmission. Original attempts16501 =16367 received200 responses +134 refused connections. Keep-alive was active:512 accepted sockets served16367 requests. A socket refusal is not an HTTP rejection.

The diagnostic leaves tiny-http.cjs byte-identical. It preloads observe-connections.cjs only into the owned Node process to observe native TCP.listen backlog, server listening/accept/close/drop events and event-loop delay. Original calls/arguments are forwarded. An optional k6 error log switch captures error_code and caller timestamps; default behavior remains unchanged. A20ms GetExtendedTcpTable observer filters to port18089. An independent uniquely named ETW session records TCPIP connect-path events, is stopped in finally, and never reuses/stops another session. No Docker, Java or ingress starts.

```powershell
cd E:\credit_risk
python .\performance\k6\diagnose_connections.py
# Offline; run with local Windows tracing permissions:
tracerpt <result-dir>\tcpip.etl -o <result-dir>\tcpip.xml -of XML -y
python .\performance\k6\analyze_connection_trace.py <result-dir>
# Separate two bounded512-socket cases; sends NO HTTP requests:
python .\performance\k6\tcp_accept_probe.py
```

Diagnostic reproduction `connection-layer-20261029_200547-5bba`:

- Native listen forwarded511, returned0. No maxConnections cap, no socket request limit, keepAliveTimeout5000ms. No server close/error/drop was observed, and every TCP-table sample retained LISTEN.
- TCPIP Event1034 recorded3501 failures to127.0.0.1:18089 with status0xC0000236; Event1183 recorded the same3501 client connections receiving RST during connect.428 connect/accept pairs succeeded in the traced connect window. This confirms the transport connection-establishment layer in this reproduction.
- Diagnostic throughput was heavily perturbed relative to the original run and included428 timeouts. Do not compare its QPS with capacity tests or attribute all stalls to Node itself. Node event-loop delay reached3777ms in this instrumented run. Tracerpt reported some events did not match schema; critical retained events contain decoded addresses, status and message. Raw ETL/XML are retained. Raw ETW timestamps are not assumed clock-aligned with JS timestamps.
- A TCP table's established count is not the listen backlog occupancy. Neither native backlog argument511 nor this trace directly proves queue saturation. Receipt of RST alone does not identify the exact server-side generating condition or exclude all WFP/security behavior.

Uninstrumented connection-only check `tcp-accept-20261029_201333-003f`:512 parallel connect attempts all succeeded;32-per10ms pacing also succeeded512/512; zero HTTP requests. This rules out a fixed total512-connection rejection limit in that check. The Python client's actual arrival pattern differs from k6, so it does not exclude a sharper connection burst or HTTP-load-dependent accept delay.

The original134 have no contemporaneous kernel trace, so they cannot be individually assigned a server-side kernel drop reason retroactively. The strongest current conclusion is TCP connect refusal before Node HTTP processing; listener/accept queue pressure under load remains a hypothesis. AFD/AcceptEx reason/queue observation would be the next targeted diagnostic, without changing backlog or production settings.

References:
- https://nodejs.org/docs/latest-v22.x/api/net.html (backlog and server connection handling)
- https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-listen (pending connection queue may cause WSAECONNREFUSED; no standard query for actual backlog)
- https://github.com/libuv/libuv/blob/v1.51.0/src/win/tcp.c (Windows listen/AcceptEx implementation)
