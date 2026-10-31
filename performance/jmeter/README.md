# Correctness load tests

Use a disposable test database and Redis instance. These plans never create fake risk results.

1. Prepare real authenticated users in `users.csv`: `userId,token,deviceId,orderId`. For 10,000 users provide 10,000 distinct accounts/devices. Log in through the demo login API, which creates real Redis tokens. Give test users a wallet balance through SQL fixtures, never a production recharge mock.
2. Seed a dedicated SKU with stock=1000 and purchaseLimit=2. Set the SKU and session with `-JskuId=... -JsessionId=...`. Avoid existing demo traffic.
3. For `03`, `04`, `05`, use a CSV containing one user so all threads share identity. `03` shares requestId; `04` generates distinct request IDs; `05` shares an existing WAITING_PAYMENT order and paymentRequestId.
4. Run `jmeter -n -t 02_flash_sale_10000_users.jmx -Jusers=users.csv -Jthreads=10000 -JskuId=... -l result.jtl`. Size/distribute load generators appropriately; this repository makes no QPS claim. Ramp time defaults to ten seconds and is configurable.
5. Capacity/limit scenarios should use a test-only risk rule configuration with velocity thresholds above generated load. Record original rule versions and restore afterwards. The attack scenario uses normal rules and repeated device identities. Otherwise rate limiting can legitimately stop most requests before inventory is reached.
6. Drain Outbox and compensation, then execute `../verify_invariants.sql`. Additionally assert exact fixture expectations: duplicate request reservation=1/order=1; user quantity<=2; flash successful reservations<=1000 and stock>=0; payment one successful debit. Compare final wallet balance with initial balance minus PAYMENT plus REFUND ledger sums.

The response assertion accepts 409/429 as business rejections, while treating 5xx/auth errors as failures. A successful HTTP response alone does not prove absence of overselling. Inspect database rows and retained Redis reservation markers.

These plans have been XML-validated only until an actual load run is recorded in TEST_RESULTS.md.
