package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.mq.TicketEventConsumer;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.payment.AutoPaymentExecutor;
import com.ticketing.payment.AutoPaymentService;
import com.ticketing.repository.jdbc.*;
import com.ticketing.ticket.ReservationIntent;
import com.ticketing.ticket.ReservationTransactionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Semantic coverage for post-trade RiskProfile architecture (scenarios A–J).
 */
class RiskProfileSemanticTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    Db db;
    StringRedisTemplate redis;
    RiskProfileService profileService;
    RiskFeatureService features;
    RiskAgentClient agent;
    RiskDecisionService risk;
    TicketEventConsumer consumer;
    ReservationTransactionService reservations;
    InventoryService inventory;
    AutoPaymentService autoPayment;
    OrderService orderService;
    WorkflowExecutionService workflow;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager ptm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(ptm);
        db = new Db(jdbc, new ObjectMapper());
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.hasKey(anyString())).thenReturn(false);
        createSchema();
        seedRules();

        JdbcRiskProfileRepository profileRepo = new JdbcRiskProfileRepository(db);
        RiskProfileCache cache = new RiskProfileCache(redis, new ObjectMapper());
        profileService = new RiskProfileService(profileRepo, cache);
        features = mock(RiskFeatureService.class);
        when(features.blacklisted(anyLong(), anyString(), anyString())).thenReturn(false);
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(0));
        agent = mock(RiskAgentClient.class);
        when(agent.analyze(anyMap())).thenReturn(RiskAgentClient.unavailable());
        risk = new RiskDecisionService(db, features,
                new RiskRuleEngine(new com.ticketing.risk.rule.RiskRuleEvaluatorRegistry(
                        Collections.singletonList(new com.ticketing.risk.rule.GenericThresholdRuleEvaluator()))),
                agent, profileService, new ObjectMapper(), new SimpleMeterRegistry());

        JdbcOrderRepository orderRepo = new JdbcOrderRepository(db);
        OrderStateMachine machine = new OrderStateMachine(orderRepo);
        OutboxService outbox = new OutboxService(db, new JdbcOutboxRepository(db));
        workflow = mock(WorkflowExecutionService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepo, new JdbcReservationRepository(db),
                new JdbcTicketInventoryRepository(db), new JdbcUserPurchaseRepository(db), machine, outbox, workflow, metrics);
        AutoPaymentExecutor executor = new AutoPaymentExecutor(orderService, machine, new JdbcPaymentRepository(db),
                outbox, workflow, metrics);
        autoPayment = spy(new AutoPaymentService(orderRepo, executor, metrics));
        consumer = new TicketEventConsumer(new JdbcMessageConsumeRepository(db), new JdbcIssuedTicketRepository(db),
                orderService, machine, risk, mock(InventoryService.class), autoPayment, workflow, metrics, ptm, outbox);
        inventory = mock(InventoryService.class);
        when(inventory.reserve(any(ReservationIntent.class))).thenReturn(1L);
        reservations = new ReservationTransactionService(
                new JdbcReservationIntentRepository(db), new JdbcTicketInventoryRepository(db),
                new JdbcUserPurchaseRepository(db), new JdbcReservationRepository(db), orderRepo,
                inventory, outbox, workflow, metrics, risk);
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY,create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR PRIMARY KEY,event_session_id VARCHAR,name VARCHAR,"
                + "price DECIMAL(18,2),total_stock INT,remaining_stock INT,sale_start_time TIMESTAMP,"
                + "sale_end_time TIMESTAMP,purchase_limit INT,status VARCHAR,version INT)");
        jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR PRIMARY KEY,fingerprint VARCHAR,"
                + "reservation_id VARCHAR UNIQUE,order_id VARCHAR UNIQUE,user_id BIGINT,sku_id VARCHAR,"
                + "quantity INT,device_id VARCHAR,ip VARCHAR,trace_id VARCHAR,status VARCHAR,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_user_purchase(user_id BIGINT,sku_id VARCHAR,quantity INT,PRIMARY KEY(user_id,sku_id))");
        jdbc.execute("CREATE TABLE tb_reservation(id VARCHAR PRIMARY KEY,request_id VARCHAR UNIQUE,user_id BIGINT,"
                + "sku_id VARCHAR,quantity INT,status VARCHAR,expires_at TIMESTAMP,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,order_no VARCHAR UNIQUE,reservation_id VARCHAR UNIQUE,"
                + "user_id BIGINT,sku_id VARCHAR,quantity INT,amount DECIMAL(18,2),device_id VARCHAR,ip VARCHAR,"
                + "status VARCHAR,version INT DEFAULT 0,trace_id VARCHAR,expires_at TIMESTAMP,"
                + "created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_outbox_message(id VARCHAR PRIMARY KEY,aggregate_type VARCHAR,aggregate_id VARCHAR,"
                + "topic VARCHAR,message_key VARCHAR UNIQUE,payload VARCHAR,status VARCHAR,retry_count INT,"
                + "next_retry_time TIMESTAMP,lease_token VARCHAR,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_message_consume_record(consumer_group VARCHAR,message_key VARCHAR,business_key VARCHAR,"
                + "status VARCHAR,PRIMARY KEY(consumer_group,message_key),UNIQUE(consumer_group,business_key))");
        jdbc.execute("CREATE TABLE tb_payment_transaction(id VARCHAR PRIMARY KEY,payment_request_id VARCHAR UNIQUE,"
                + "order_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),type VARCHAR,status VARCHAR,"
                + "success_key VARCHAR UNIQUE,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE tb_issued_ticket(id VARCHAR PRIMARY KEY,order_id VARCHAR,sequence_no INT)");
        jdbc.execute("CREATE TABLE tb_risk_rule(rule_code VARCHAR PRIMARY KEY,enabled BOOLEAN,weight INT,config VARCHAR,version INT)");
        jdbc.execute("CREATE TABLE tb_risk_decision(id VARCHAR PRIMARY KEY,order_id VARCHAR,phase VARCHAR,decision VARCHAR,"
                + "score INT,features VARCHAR,matched_rules VARCHAR,agent_analysis VARCHAR,context_key VARCHAR,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_risk_profile(id VARCHAR PRIMARY KEY,subject_type VARCHAR,subject_value VARCHAR,"
                + "risk_level VARCHAR,decision VARCHAR,score INT,reason_codes VARCHAR,source_order_id VARCHAR,"
                + "source_decision_id VARCHAR,version INT,expires_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,"
                + "UNIQUE(subject_type,subject_value))");
        jdbc.update("INSERT INTO tb_user VALUES(1,CURRENT_TIMESTAMP)");
    }

    private void seedRules() {
        jdbc.update("INSERT INTO tb_risk_rule VALUES('DECISION_POLICY',true,0,'{\"review\":50,\"block\":100,\"agentMaxAdjustment\":20,\"rateLimit\":180}',1)");
        // Deep-path signal only (not fast:true) so REVIEW vs BLOCK is controllable without double-counting.
        jdbc.update("INSERT INTO tb_risk_rule VALUES('SIGNAL',true,90,'{\"feature\":\"signal\",\"threshold\":1,\"fast\":false}',1)");
    }

    private Map<String, Double> featureMap(double signal) {
        Map<String, Double> f = new HashMap<>();
        f.put("signal", signal);
        f.put("ipMinute", 1d);
        f.put("deviceMinute", 1d);
        f.put("userMinute", 1d);
        f.put("historyCount", 0d);
        f.put("deviceAccounts", 0d);
        f.put("paymentFailures", 0d);
        return f;
    }

    private void seedSku(String id) {
        jdbc.update("INSERT INTO tb_ticket_sku VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, "session-1", "T", new BigDecimal("10.00"), 10, 10,
                new Timestamp(System.currentTimeMillis() - 1000),
                new Timestamp(System.currentTimeMillis() + 3_600_000),
                5, "ON_SALE", 0);
    }

    private void seedIntent(String requestId, String orderId, String sku) {
        jdbc.update("INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,"
                        + "quantity,device_id,ip,trace_id,status) VALUES(?,?,?,?,1,?,1,'device-1','10.0.0.1','t','REQUESTED')",
                requestId, "fp", "res-" + orderId, orderId, sku);
    }

    private void seedOrder(String id, String status) {
        jdbc.update("INSERT INTO tb_ticket_order(id,order_no,reservation_id,user_id,sku_id,quantity,amount,device_id,ip,"
                        + "status,version,trace_id,expires_at,updated_at) VALUES(?,?,?,1,'sku',1,40,'device-1','10.0.0.1',?,0,'t',?,CURRENT_TIMESTAMP)",
                id, id, "r-" + id, status, new Timestamp(System.currentTimeMillis() + 600000));
    }

    private void putProfile(String type, String value, String decision, long expiresOffsetMs) {
        jdbc.update("INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,reason_codes,"
                        + "source_order_id,source_decision_id,version,expires_at) VALUES(?,?,?,?,?,?,?,?,?,0,?)",
                UUID.randomUUID().toString(), type, value, "HIGH", decision, 100, "TEST",
                "src", "dec", new Timestamp(System.currentTimeMillis() + expiresOffsetMs));
    }

    /** A: BLOCK profile rejects before Redis Lua / reservation / order. */
    @Test
    void blockProfileRejectsBeforeReserveSideEffects() {
        putProfile("USER", "1", "BLOCK", 60_000);
        assertThrows(BusinessException.class, () -> risk.fast(1L, "device-1", "10.0.0.1"));
        verify(inventory, never()).reserve(any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
    }

    /** B: no profile → reserve creates WAITING_PAYMENT and schedules auto-pay without waiting Agent. */
    @Test
    void noProfileReservesAndSchedulesAutoPayWithoutAgent() {
        seedSku("sku-b");
        seedIntent("req-b", "ord-b", "sku-b");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(0));

        Map<String, Object> result = tx.execute(s -> reservations.reserve("req-b", "session-1"));
        assertEquals("WAITING_PAYMENT", result.get("status"));
        verify(autoPayment, never()).attempt(anyString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='AUTO_PAYMENT:ord-b' AND status='PENDING'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RISK_CHECK:ord-b'", Integer.class));
        // Durable delivery: consume AUTO_PAYMENT → attempt → PAID
        tx.execute(s -> {
            consumer.consume("AUTO_PAYMENT:ord-b", "ord-b", "AUTO_PAYMENT");
            return null;
        });
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-b'", String.class));
        verify(autoPayment, times(1)).attempt("ord-b");
        verify(agent, never()).analyze(anyMap());
    }

    /** C+D: deep BLOCK updates profile but leaves ISSUED order untouched. */
    @Test
    void deepBlockUpdatesProfileWithoutTouchingCurrentOrder() {
        seedOrder("ord-c", "ISSUED");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        saveReviewSnapshot("ord-c");

        consumer.consume("RISK_CHECK:ord-c", "ord-c", "RISK_CHECK");

        assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-c'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation WHERE status='RELEASED'", Integer.class));
        assertEquals("BLOCK", jdbc.queryForObject(
                "SELECT decision FROM tb_risk_profile WHERE subject_type='USER' AND subject_value='1'", String.class));
        verify(redis, atLeastOnce()).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(Collections.singletonList("risk:profile:user:1")), anyString(), anyString(), anyString());
    }

    /** E: second reserve blocked after async profile BLOCK. */
    @Test
    void nextRequestBlockedAfterAsyncProfileUpdate() {
        seedOrder("ord-e", "ISSUED");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        saveReviewSnapshot("ord-e");
        consumer.consume("RISK_CHECK:ord-e", "ord-e", "RISK_CHECK");
        assertThrows(BusinessException.class, () -> risk.fast(1L, "device-1", "10.0.0.1"));
    }

    /** F: REVIEW calls Agent; Java clamp updates profile; order unchanged. */
    @Test
    void reviewCallsAgentThenUpdatesProfileOnly() {
        seedOrder("ord-f", "ISSUED");
        saveReviewSnapshot("ord-f");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        Map<String, Object> advice = new HashMap<>();
        advice.put("aiAvailable", true);
        advice.put("scoreAdjustment", 5);
        when(agent.analyze(anyMap())).thenReturn(advice);

        consumer.consume("RISK_CHECK:ord-f", "ord-f", "RISK_CHECK");

        verify(agent, times(1)).analyze(anyMap());
        assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-f'", String.class));
        assertEquals("REVIEW", jdbc.queryForObject(
                "SELECT decision FROM tb_risk_profile WHERE subject_type='USER' AND subject_value='1'", String.class));
        assertEquals(95, jdbc.queryForObject(
                "SELECT score FROM tb_risk_profile WHERE subject_type='USER' AND subject_value='1'", Integer.class));
    }

    /** G: Agent unavailable → pure Java profile; order intact. */
    @Test
    void agentUnavailableStillWritesJavaProfile() {
        seedOrder("ord-g", "ISSUED");
        saveReviewSnapshot("ord-g");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        when(agent.analyze(anyMap())).thenReturn(RiskAgentClient.unavailable());

        consumer.consume("RISK_CHECK:ord-g", "ord-g", "RISK_CHECK");

        assertEquals("REVIEW", jdbc.queryForObject(
                "SELECT decision FROM tb_risk_profile WHERE subject_type='USER' AND subject_value='1'", String.class));
        assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-g'", String.class));
    }

    /** H: duplicate RISK_CHECK is idempotent for consume + profile source_decision_id. */
    @Test
    void duplicateRiskCheckDoesNotDoubleWriteSideEffects() {
        seedOrder("ord-h", "ISSUED");
        saveReviewSnapshot("ord-h");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        consumer.consume("RISK_CHECK:ord-h", "ord-h", "RISK_CHECK");
        int profiles = jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile", Integer.class);
        int decisions = jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_decision", Integer.class);
        consumer.consume("RISK_CHECK:ord-h", "ord-h", "RISK_CHECK");
        assertEquals(profiles, jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile", Integer.class));
        assertEquals(decisions, jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_decision", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record", Integer.class));
        assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-h'", String.class));
    }

    /** I: expired profile does not block. */
    @Test
    void expiredProfileDoesNotBlock() {
        putProfile("USER", "1", "BLOCK", -60_000);
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(0));
        assertDoesNotThrow(() -> risk.fast(1L, "device-1", "10.0.0.1"));
    }

    /** J: blacklist still wins without Agent. */
    @Test
    void blacklistBlocksWithoutAgent() {
        when(features.blacklisted(1L, "device-1", "10.0.0.1")).thenReturn(true);
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(0));
        assertThrows(BusinessException.class, () -> risk.fast(1L, "device-1", "10.0.0.1"));
        verify(agent, never()).analyze(anyMap());
    }

    private void saveReviewSnapshot(String orderId) {
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        risk.saveAdmission(orderId, risk.admit(1L, "device-1", "10.0.0.1"), "device-1", "10.0.0.1");
        if (orderId.equals("ord-c") || orderId.equals("ord-e")) {
            Map<String, Object> advice = new HashMap<>();
            advice.put("aiAvailable", true);
            advice.put("riskLevel", "HIGH");
            advice.put("confidence", 0.9);
            advice.put("reasons", Collections.singletonList("Confirmed linked-account abuse"));
            advice.put("scoreAdjustment", 0);
            when(agent.analyze(anyMap())).thenReturn(advice);
        }
    }

    @Test
    void threeAdmissionClassesNeverCallAiAtEntry() {
        assertEquals("PASS", risk.admit(1L, "device-1", "10.0.0.1").get("decision"));
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        assertEquals("REVIEW", risk.admit(1L, "device-1", "10.0.0.1").get("decision"));
        jdbc.update("UPDATE tb_risk_rule SET weight=100 WHERE rule_code='SIGNAL'");
        assertThrows(BusinessException.class, () -> risk.admit(1L, "device-1", "10.0.0.1"));
        verifyNoInteractions(agent);
    }

    @Test
    void reviewSchedulesInvestigationOnlyInSuccessfulIssueTransaction() {
        seedSku("sku-review");
        seedIntent("req-review", "ord-review", "sku-review");
        when(features.collect(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyDouble()))
                .thenReturn(featureMap(1));
        Map<String, Object> admission = risk.admit(1L, "device-1", "10.0.0.1");
        tx.execute(s -> reservations.reserve("req-review", "session-1", admission));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RISK_CHECK:ord-review'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile", Integer.class));
        consumer.consume("AUTO_PAYMENT:ord-review", "ord-review", "AUTO_PAYMENT");
        assertThrows(IllegalStateException.class, () -> consumer.consume("RISK_CHECK:ord-review", "ord-review", "RISK_CHECK"));
        verifyNoInteractions(agent);
        doThrow(new IllegalStateException("checkpoint unavailable")).when(workflow)
                .record(eq("ord-review"), anyString(), eq("ISSUE"), any());
        assertThrows(IllegalStateException.class, () -> consumer.consume("ISSUE:ord-review", "ord-review", "ISSUE"));
        assertEquals("PAID", orderService.find("ord-review").getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RISK_CHECK:ord-review'", Integer.class));
        reset(workflow);
        consumer.consume("ISSUE:ord-review", "ord-review", "ISSUE");
        consumer.consume("ISSUE:ord-review", "ord-review", "ISSUE");
        assertEquals("ISSUED", orderService.find("ord-review").getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RISK_CHECK:ord-review'", Integer.class));
        verifyNoInteractions(agent);
    }

    @Test
    void slowAiHoldsNeitherTradingLockNorTransactionAndBlocksOnlyAfterCompletion() throws Exception {
        seedOrder("ord-slow", "ISSUED");
        saveReviewSnapshot("ord-slow");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        when(agent.analyze(anyMap())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Map<?, ?> input = call.getArgument(0);
            assertEquals("ISSUED", ((Map<?, ?>) input.get("order")).get("status"));
            entered.countDown();
            assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Map<String, Object> answer = new HashMap<>();
            answer.put("aiAvailable", true); answer.put("riskLevel", "HIGH");
            answer.put("confidence", 0.9); answer.put("scoreAdjustment", 0);
            answer.put("reasons", Collections.singletonList("Confirmed abuse"));
            return answer;
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> investigation = pool.submit(() -> consumer.consume("RISK_CHECK:ord-slow", "ord-slow", "RISK_CHECK"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<String> trading = pool.submit(() -> tx.execute(s -> orderService.lock("ord-slow").getStatus()));
            assertEquals("ISSUED", trading.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertDoesNotThrow(() -> risk.fast(1L, "device-1", "10.0.0.1"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile", Integer.class));
            finish.countDown();
            investigation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(BusinessException.class, () -> risk.fast(1L, "device-1", "10.0.0.1"));
            assertEquals("ISSUED", orderService.find("ord-slow").getStatus());
        } finally {
            finish.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void confidentLowRiskCanResolveSuspicionWithoutChangingOrder() {
        seedOrder("ord-low", "ISSUED"); saveReviewSnapshot("ord-low");
        Map<String, Object> advice = new HashMap<>();
        advice.put("aiAvailable", true); advice.put("riskLevel", "LOW");
        advice.put("confidence", 0.9); advice.put("scoreAdjustment", 0);
        advice.put("reasons", Collections.singletonList("Normal purchase pattern"));
        when(agent.analyze(anyMap())).thenReturn(advice);
        consumer.consume("RISK_CHECK:ord-low", "ord-low", "RISK_CHECK");
        assertEquals("PASS", jdbc.queryForObject("SELECT decision FROM tb_risk_profile WHERE subject_type='USER'", String.class));
        assertEquals("ISSUED", orderService.find("ord-low").getStatus());
    }

    @Test
    void lowRiskCannotClearActiveBlockAndOlderResultsCannotOverwriteNewerPortrait() {
        seedOrder("old", "ISSUED"); seedOrder("new", "ISSUED");
        jdbc.update("UPDATE tb_ticket_order SET created_at=? WHERE id='old'", new Timestamp(System.currentTimeMillis()-60000));
        jdbc.update("UPDATE tb_ticket_order SET created_at=CURRENT_TIMESTAMP WHERE id='new'");
        Map<String, Object> recent = new HashMap<>();
        recent.put("id", "recent"); recent.put("decision", "REVIEW"); recent.put("score", 60);
        tx.executeWithoutResult(s -> profileService.applyFromDecision(orderService.find("new"), recent));
        Map<String, Object> stale = new HashMap<>();
        stale.put("id", "stale"); stale.put("decision", "BLOCK"); stale.put("score", 100);
        tx.executeWithoutResult(s -> profileService.applyFromDecision(orderService.find("old"), stale));
        assertEquals("recent", jdbc.queryForObject("SELECT source_decision_id FROM tb_risk_profile WHERE subject_type='USER'", String.class));
        recent.put("id", "blocked"); recent.put("decision", "BLOCK"); recent.put("score", 100);
        tx.executeWithoutResult(s -> profileService.applyFromDecision(orderService.find("new"), recent));
        recent.put("id", "low"); recent.put("decision", "PASS"); recent.put("score", 0);
        tx.executeWithoutResult(s -> profileService.applyFromDecision(orderService.find("new"), recent));
        assertEquals("BLOCK", jdbc.queryForObject("SELECT decision FROM tb_risk_profile WHERE subject_type='USER'", String.class));
    }
}
