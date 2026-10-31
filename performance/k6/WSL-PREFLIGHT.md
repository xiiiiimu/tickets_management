# WSL2 Windows-host connectivity prerequisite

```powershell
cd E:\credit_risk
python .\performance\k6\wsl_preflight.py
```

This command starts only the byte-identical tiny-http.cjs on its existing127.0.0.1:18089 listener, validates200/OK from Windows, discovers the Ubuntu default gateway and networking mode, and performs10 direct-IP HTTP checks from Ubuntu. Proxies are explicitly bypassed. Windows server receive counters distinguish a response from this owned service from another endpoint. The owned Node process is stopped in finally. No load runs in this preflight. It never calls wsl --shutdown/--terminate, changes binding/backlog, edits firewall/portproxy/networking, or operates Docker.

Linux k6 v2.3.0 is installed portably at `/mnt/e/credit_risk/.tools/k6-v2.3.0-linux-amd64/k6`. The official Grafana Linux release archive was downloaded and matched to both official release API digest and checksums, then extracted and executed inside Ubuntu. No apt repositories, system PATH, CPU/memory limit or WSL network configuration is changed.

```powershell
wsl -d Ubuntu --exec /mnt/e/credit_risk/.tools/k6-v2.3.0-linux-amd64/k6 version
wsl -d Ubuntu --exec wslinfo --networking-mode
wsl -d Ubuntu --exec ip -4 route
```

In NAT mode a Linux client normally accesses Windows using its host gateway IP, not Linux loopback. A Windows service bound only to127.0.0.1 does not accept connections addressed to the gateway's IP. Microsoft documents that remote-IP access requires a suitable application binding; mirrored networking permits host/WSL localhost access. The current task prohibits changing binding or network mode, so a failed preflight must stop the test rather than silently alter these variables or add a forwarder.

Reference: https://learn.microsoft.com/en-us/windows/wsl/networking

The authorized subsequent load plan is1000/s128VU,3000/s256VU,5000/s256VU, and5000/s512VU only if the previous5000 case fails with adequate resources. All have5-second windows and keep-alive; pass is>=4750 completed/s,<=1% dropped and near-zero errors with acceptable resources. These are planned tiers, not measurements. No throughput should be reported if connectivity is blocked. WSL and Windows share the physical host and are not separate benchmark machines.
