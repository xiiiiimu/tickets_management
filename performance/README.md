# Performance acceptance

Formal report: [`../PERFORMANCE_REPORT.md`](../PERFORMANCE_REPORT.md)  
Latest formal results: `../results/20260918-094904/` (do **not** treat `20260918-040002` as formal).

## Entry

```powershell
# From repo root; platform must be UP on :8082 first
powershell -NoProfile -ExecutionPolicy Bypass -File performance/run-performance.ps1
```

Notes:

- Non-GUI only; HTML report disabled to reduce client memory.
- Do **not** pass `-ThreadLevels 20,50,100` from an outer PowerShell that expands arrays (can stringify to `2050100`). Use script defaults, or `-ThreadLevelsCsv "20,50,100"` once that parameter is wired.
- Stock ladder: 20/50/100 × 3 with per-round SQL in `sql/validate_stock20_round.sql`.
- JMeter plans retained for scenarios: `stock_contention.jmx`, `duplicate_request_id.jmx`, `fingerprint_conflict.jmx`, `purchase_limit.jmx`, `risk_profile_block.jmx`.

## Semantics reminder

HTTP 200 alone does not prove no oversell. Always check `validation-*.txt` / DB invariants. HTTP 409 on BLOCK is business reject, not system failure.
