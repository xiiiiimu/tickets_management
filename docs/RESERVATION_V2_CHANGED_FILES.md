# 本批实际源码修改清单

比较基准是本任务开始前的完整工作树源码快照，不是 Git HEAD；因此不会把此前已存在的 Armory、库存桶、P0/P1 等成果算成本批新建文件。

| 阶段 | 变更 | 文件 | SHA256 前12位 |
|---|---|---|---|
| 2 Armory | added | `ticket-risk-platform/src/main/java/com/ticketing/sale/SaleManifestCache.java` | `b92c30ceeff0` |
| 2 Armory | added | `ticket-risk-platform/src/test/java/com/ticketing/sale/ArmoryRuntimeGateTest.java` | `70d23ca7d2e4` |
| 2 Armory | added | `ticket-risk-platform/src/test/java/com/ticketing/sale/SaleManifestCacheTest.java` | `2f8a90e8ea8a` |
| 2 Armory | modified | `ticket-risk-platform/src/main/java/com/ticketing/sale/TicketSaleArmory.java` | `0c70dc678723` |
| 3 features / 4 fence | added | `ticket-risk-platform/src/main/java/com/ticketing/risk/RiskHistorySummary.java` | `c79b4fc1c5fe` |
| 3 features / 4 fence | added | `ticket-risk-platform/src/main/resources/db/migration/V105__risk_history_summary.sql` | `5733e4a8474c` |
| 3 features / 4 fence | added | `ticket-risk-platform/src/test/java/com/ticketing/risk/RiskHistorySummaryTest.java` | `a80a2c64469c` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/RiskProfileRepository.java` | `90af6a23fbbd` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcOrderRepository.java` | `fb7998668611` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcPaymentRepository.java` | `c1f17f344d63` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcRiskProfileRepository.java` | `0a7ded71f3ac` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/risk/RiskFeatureService.java` | `5abc4482a18f` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/java/com/ticketing/risk/RiskProfileService.java` | `c6eb506a3bfb` |
| 3 features / 4 fence | modified | `ticket-risk-platform/src/main/resources/lua/risk_sliding_window.lua` | `c3c1d1ac64d4` |
| 3-6 additive schema | modified | `ticket-risk-platform/src/main/resources/schema.sql` | `48f81451fa91` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/risk/RiskSubjectFence.java` | `90025ede664a` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationAdmissionThrottle.java` | `16fee5b61487` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationDispatch.java` | `75e0eedb949f` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationModeController.java` | `7643c39663ba` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationRuntimeRecovery.java` | `3afb6542f6af` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationV2Controller.java` | `870a584f59f5` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationV2Service.java` | `4205d9c20717` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationV2Transactions.java` | `2b26bd53571b` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/resources/application-reservations-v2.yml` | `d3fb3475564c` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/resources/db/migration/V106__reservation_dispatch.sql` | `e662a6cb38d0` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/main/resources/lua/abort_reservation_v2.lua` | `8f835a0425af` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/benchmark/InventoryOnlyApplication.java` | `831137dda995` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/integration/ReservationV2IT.java` | `7fdc371fca0b` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/risk/RiskSubjectFenceTest.java` | `9760474506e8` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/ticket/LegacyEntryIsolationTest.java` | `30e150e1526d` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/ticket/ReservationAdmissionThrottleTest.java` | `0c1d652e6f98` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/ticket/ReservationDispatchTest.java` | `89db692c3835` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/ticket/ReservationOwnershipTest.java` | `4bff80fde9a2` |
| 4 dispatch / reliability | added | `ticket-risk-platform/src/test/java/com/ticketing/workflow/service/InvestigationEvidenceTest.java` | `fc9a801b5e61` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/config/AuthInterceptorConstants.java` | `4cc75ff7cc4e` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/infrastructure/AdminController.java` | `c8483814f0ba` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/infrastructure/PlatformMetrics.java` | `9a6499bc2ec6` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/infrastructure/TaskPools.java` | `2048df4a609d` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/inventory/InventoryService.java` | `6f606ac14e29` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/inventory/ReservationRecoveryService.java` | `961461098e1e` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/TicketInventoryRepository.java` | `d114c3c2c5e3` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcTicketInventoryRepository.java` | `b91cc52f7f5c` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/risk/RiskDecisionService.java` | `2165af396d28` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationIntent.java` | `76bb6bca0025` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/java/com/ticketing/ticket/TicketController.java` | `bc158160e0e8` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/main/resources/lua/reserve_ticket_bucket.lua` | `190052b5a5ce` |
| 4 dispatch / reliability | modified | `ticket-risk-platform/src/test/java/com/ticketing/infrastructure/PlatformMetricsTest.java` | `e196ad932d59` |
| 5 payment | added | `ticket-risk-platform/src/main/java/com/ticketing/payment/GatewayPaymentController.java` | `dfe2449c4c59` |
| 5 payment | added | `ticket-risk-platform/src/main/java/com/ticketing/payment/GatewayPaymentService.java` | `0708c52cf272` |
| 5 payment | added | `ticket-risk-platform/src/main/java/com/ticketing/payment/HttpPaymentGateway.java` | `42aa760a19cb` |
| 5 payment | added | `ticket-risk-platform/src/main/java/com/ticketing/payment/PaymentGateway.java` | `8a7d512c0b14` |
| 5 payment | added | `ticket-risk-platform/src/main/java/com/ticketing/payment/ReservationPaymentPolicy.java` | `e6b20af62527` |
| 5 payment | added | `ticket-risk-platform/src/main/resources/db/migration/V107__reservation_deadline_precision.sql` | `983395942394` |
| 5 payment | added | `ticket-risk-platform/src/main/resources/db/migration/V108__gateway_payment_attempt.sql` | `467a87b02f14` |
| 5 payment | added | `ticket-risk-platform/src/test/java/com/ticketing/payment/GatewayCallbackAuthenticationTest.java` | `6d721cb3c214` |
| 5 payment | added | `ticket-risk-platform/src/test/java/com/ticketing/payment/GatewayPaymentServiceTest.java` | `8879cd5cd7cd` |
| 5 payment | added | `ticket-risk-platform/src/test/java/com/ticketing/payment/ReservationPaymentPolicyTest.java` | `1b38f273d1b9` |
| 5 payment | modified | `ticket-risk-platform/src/main/java/com/ticketing/order/OrderController.java` | `61cdb179f060` |
| 5 payment | modified | `ticket-risk-platform/src/main/java/com/ticketing/order/OrderService.java` | `151ef96c62d6` |
| 5 payment | modified | `ticket-risk-platform/src/main/java/com/ticketing/order/OrderStateMachine.java` | `e8d6a3a4b9af` |
| 5 payment | modified | `ticket-risk-platform/src/main/java/com/ticketing/payment/AutoPaymentExecutor.java` | `32e7dcaa1e82` |
| 5 payment | modified | `ticket-risk-platform/src/main/java/com/ticketing/payment/WalletService.java` | `01095dcdc8d6` |
| 6 fulfillment | added | `ticket-risk-platform/src/main/java/com/ticketing/notification/OrderNotificationController.java` | `c975acc91543` |
| 6 fulfillment | added | `ticket-risk-platform/src/main/java/com/ticketing/notification/OrderNotificationService.java` | `deb8705165a6` |
| 6 fulfillment | added | `ticket-risk-platform/src/main/resources/db/migration/V109__order_notification.sql` | `e75171955c0a` |
| 6 fulfillment | added | `ticket-risk-platform/src/test/java/com/ticketing/notification/OrderNotificationServiceTest.java` | `7bb4d030dfaf` |
| 6 fulfillment | modified | `ticket-risk-platform/src/main/java/com/ticketing/mq/TicketEventConsumer.java` | `9222cdf33a2d` |
| 6 fulfillment | modified | `ticket-risk-platform/src/main/java/com/ticketing/repository/jdbc/JdbcOutboxRepository.java` | `16059a25dc26` |
| 6 fulfillment | modified | `ticket-risk-platform/src/main/java/com/ticketing/workflow/service/WorkflowExecutionService.java` | `e523be6e48b9` |
| 6 fulfillment | modified | `ticket-risk-platform/src/main/java/com/ticketing/workflow/service/WorkflowPersistenceService.java` | `6d946eba96d3` |
| 6 fulfillment | modified | `ticket-risk-web/src/App.tsx` | `7f06c95d6599` |
| 7 metrics | modified | `ticket-risk-platform/src/main/java/com/ticketing/config/BusinessMetricsAspect.java` | `3269654e2eaf` |

配套新增：`performance/run-reservation-v2-tests.ps1`、`performance/run-reservation-pipeline-benchmark.ps1`、`performance/reservation_pipeline_benchmark.py`、`performance/analyze_reservation_pipeline.py`、本清单生成器、`docker/credit_risk/compose-v2-tests.yaml`、`docker/grafana/dashboards/reservation-v2.json`、本批设计/操作/性能报告。

源码增量审阅补丁：`.tmp/architecture-rebuild-20261028/implementation-only.patch`。只生成补丁，不应用、不暂存、不提交或回滚任何文件。跨阶段共享文件集中列出，不伪装成可任意顺序应用的独立补丁。

原有变更保护：`preexisting.patch`、`preexisting-status.txt`、`source-before.zip`、`source-before.json` 和 `App-before.tsx` 均保留。测试日志/数据库/缓存数据保留，不执行清理。
