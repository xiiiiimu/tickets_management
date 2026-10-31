package com.ticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.audit.service.AuditLogService;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.mq.TicketEventConsumer;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.jdbc.*;
import com.ticketing.review.ManualReviewService;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.ticket.ReservationIntent;
import com.ticketing.ticket.ReservationTransactionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Demo auto-pay + durable AUTO_PAYMENT outbox delivery (Round 3).
 */
class AutoPaymentServiceTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    Db db;
    AutoPaymentExecutor executor;
    AutoPaymentExecutor executorSpy;
    AutoPaymentService autoPayment;
    AutoPaymentService autoPaymentSpy;
    TicketEventConsumer consumer;
    ManualReviewService reviews;
    RiskDecisionService risk;
    OutboxService outbox;
    JdbcOrderRepository orderRepo;
    OrderService orderService;
    WorkflowExecutionService workflow;
    SimpleMeterRegistry metrics;
    ReservationTransactionService reservations;
    InventoryService inventory;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager ptm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(ptm);
        db = new Db(jdbc, new ObjectMapper());
        createSchema();
        orderRepo = new JdbcOrderRepository(db);
        OrderStateMachine machine = new OrderStateMachine(orderRepo);
        outbox = spy(new OutboxService(db, new JdbcOutboxRepository(db)));
        workflow = mock(WorkflowExecutionService.class);
        metrics = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepo, new JdbcReservationRepository(db),
                new JdbcTicketInventoryRepository(db), new JdbcUserPurchaseRepository(db), machine, outbox, workflow, metrics);
        risk = mock(RiskDecisionService.class);
        when(risk.evaluate(any(TicketOrder.class), anyString(), anyString(), anyString()))
                .thenReturn(map("decision", "PASS", "id", "d1", "score", 0));
        executor = new AutoPaymentExecutor(orderService, machine, new JdbcPaymentRepository(db), outbox, workflow, metrics);
        executorSpy = spy(executor);
        autoPayment = new AutoPaymentService(orderRepo, executorSpy, metrics);
        autoPaymentSpy = spy(autoPayment);
        reviews = new ManualReviewService(new JdbcManualReviewRepository(db), orderService,
                machine, mock(AuditLogService.class), workflow);
        consumer = new TicketEventConsumer(new JdbcMessageConsumeRepository(db), new JdbcIssuedTicketRepository(db),
                orderService, machine, risk, mock(InventoryService.class), autoPaymentSpy, workflow, metrics, ptm, outbox);
        inventory = mock(InventoryService.class);
        when(inventory.reserve(any(ReservationIntent.class))).thenReturn(1L);
        reservations = new ReservationTransactionService(
                new JdbcReservationIntentRepository(db), new JdbcTicketInventoryRepository(db),
                new JdbcUserPurchaseRepository(db), new JdbcReservationRepository(db), orderRepo,
                inventory, outbox, workflow, metrics, risk);
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,order_no VARCHAR UNIQUE,reservation_id VARCHAR,"
                + "user_id BIGINT,sku_id VARCHAR,quantity INT,amount DECIMAL(18,2),device_id VARCHAR,ip VARCHAR,"
                + "status VARCHAR,version INT DEFAULT 0,trace_id VARCHAR,expires_at TIMESTAMP,updated_at TIMESTAMP,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_payment_transaction(id VARCHAR PRIMARY KEY,payment_request_id VARCHAR UNIQUE,"
                + "order_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),type VARCHAR,status VARCHAR,"
                + "success_key VARCHAR UNIQUE,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE tb_manual_review_ticket(id VARCHAR PRIMARY KEY,order_id VARCHAR,risk_decision_id VARCHAR UNIQUE,"
                + "phase VARCHAR,status VARCHAR,request_id VARCHAR,reviewer_id BIGINT,version INT DEFAULT 0,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_message_consume_record(consumer_group VARCHAR,message_key VARCHAR,business_key VARCHAR,"
                + "status VARCHAR,PRIMARY KEY(consumer_group,message_key),UNIQUE(consumer_group,business_key))");
        jdbc.execute("CREATE TABLE tb_issued_ticket(id VARCHAR PRIMARY KEY,order_id VARCHAR,sequence_no INT)");
        jdbc.execute("CREATE TABLE tb_reservation(id VARCHAR PRIMARY KEY,request_id VARCHAR UNIQUE,user_id BIGINT,"
                + "sku_id VARCHAR,quantity INT,status VARCHAR,expires_at TIMESTAMP,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR PRIMARY KEY,event_session_id VARCHAR,name VARCHAR,"
                + "price DECIMAL(18,2),total_stock INT,remaining_stock INT,sale_start_time TIMESTAMP,"
                + "sale_end_time TIMESTAMP,purchase_limit INT,status VARCHAR,version INT)");
        jdbc.execute("CREATE TABLE tb_user_purchase(user_id BIGINT,sku_id VARCHAR,quantity INT,PRIMARY KEY(user_id,sku_id))");
        jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR PRIMARY KEY,fingerprint VARCHAR,"
                + "reservation_id VARCHAR UNIQUE,order_id VARCHAR UNIQUE,user_id BIGINT,sku_id VARCHAR,"
                + "quantity INT,device_id VARCHAR,ip VARCHAR,trace_id VARCHAR,status VARCHAR,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_outbox_message(id VARCHAR PRIMARY KEY,aggregate_type VARCHAR,aggregate_id VARCHAR,"
                + "topic VARCHAR,message_key VARCHAR UNIQUE,payload VARCHAR,status VARCHAR,retry_count INT DEFAULT 0,"
                + "next_retry_time TIMESTAMP,lease_token VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP)");
    }

    private void seedOrder(String id, String status) {
        jdbc.update("INSERT INTO tb_ticket_order(id,order_no,reservation_id,user_id,sku_id,quantity,amount,device_id,ip,"
                        + "status,version,trace_id,expires_at,updated_at) VALUES(?,?,?,1,'sku',1,40,'device-1','127.0.0.1',?,0,'trace',?,CURRENT_TIMESTAMP)",
                id, id, "r-" + id, status, new Timestamp(System.currentTimeMillis() + 600000));
    }

    @Test
    void waitingPaymentConfirmsWithoutRiskGate() {
        seedOrder("o-pass", "WAITING_PAYMENT");
        Map<String, Object> paid = autoPayment.attempt("o-pass");
        assertEquals("SUCCESS", paid.get("status"));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='o-pass'", String.class));
        verify(outbox, times(1)).append("o-pass", "ISSUE");
        verify(risk, never()).evaluate(any(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateAutoPayConfirmsOnce() {
        seedOrder("o-idem", "WAITING_PAYMENT");
        Map<String, Object> first = autoPayment.attempt("o-idem");
        Map<String, Object> second = autoPayment.attempt("o-idem");
        assertEquals("SUCCESS", first.get("status"));
        assertNull(second);
        Map<String, Object> replay = executor.execute("o-idem");
        assertEquals(first.get("id"), replay.get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE status='SUCCESS'", Integer.class));
        verify(outbox, times(1)).append("o-idem", "ISSUE");
    }

    @Test
    void reserveWritesAutoPaymentOutboxInSameTransaction() {
        jdbc.update("INSERT INTO tb_ticket_sku VALUES('sku1','sess','T',10.00,10,10,?,?,5,'ON_SALE',0)",
                new Timestamp(System.currentTimeMillis() - 1000), new Timestamp(System.currentTimeMillis() + 3600000));
        jdbc.update("INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,"
                        + "quantity,device_id,ip,trace_id,status) VALUES('req1','fp','res1','ord1',1,'sku1',1,'d','1.1.1.1','t','REQUESTED')");
        Map<String, Object> result = tx.execute(s -> reservations.reserve("req1", "sess"));
        assertEquals("WAITING_PAYMENT", result.get("status"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='AUTO_PAYMENT:ord1' AND status='PENDING'", Integer.class));
        String payload = jdbc.queryForObject("SELECT payload FROM tb_outbox_message WHERE message_key='AUTO_PAYMENT:ord1'", String.class);
        assertTrue(payload.contains("AUTO_PAY:ord1"));
        verify(autoPaymentSpy, never()).attempt(anyString());
    }

    @Test
    void reserveRollbackDoesNotLeaveAutoPaymentOutbox() {
        jdbc.update("INSERT INTO tb_ticket_sku VALUES('sku2','sess','T',10.00,10,10,?,?,5,'ON_SALE',0)",
                new Timestamp(System.currentTimeMillis() - 1000), new Timestamp(System.currentTimeMillis() + 3600000));
        jdbc.update("INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,"
                        + "quantity,device_id,ip,trace_id,status) VALUES('req2','fp','res2','ord2',1,'sku2',1,'d','1.1.1.1','t','REQUESTED')");
        when(inventory.reserve(any(ReservationIntent.class))).thenThrow(new IllegalStateException("redis down"));
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> reservations.reserve("req2", "sess")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key LIKE 'AUTO_PAYMENT:%'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
    }

    @Test
    void mqConsumerDuplicateDeliveryPaysOnce() {
        seedOrder("o-mq", "WAITING_PAYMENT");
        consumer.consume("AUTO_PAYMENT:o-mq", "o-mq", "AUTO_PAYMENT");
        consumer.consume("AUTO_PAYMENT:o-mq", "o-mq", "AUTO_PAYMENT");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE status='SUCCESS'", Integer.class));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='o-mq'", String.class));
        verify(autoPaymentSpy, times(1)).attempt("o-mq");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record WHERE business_key='AUTO_PAYMENT:o-mq'", Integer.class));
    }

    @Test
    void paymentRequestIdStableForSameOrder() {
        assertEquals("AUTO_PAY:ORD-1", AutoPaymentService.paymentRequestId("ORD-1"));
        assertEquals(AutoPaymentService.paymentRequestId("x"), AutoPaymentService.paymentRequestId("x"));
    }

    @Test
    void mqSystemFailureDoesNotAckConsumeRecord() {
        seedOrder("o-fail", "WAITING_PAYMENT");
        AutoPaymentExecutor boom = mock(AutoPaymentExecutor.class);
        when(boom.execute("o-fail")).thenThrow(new RuntimeException("db unavailable"));
        AutoPaymentService svc = new AutoPaymentService(orderRepo, boom, new SimpleMeterRegistry());
        TicketEventConsumer c = new TicketEventConsumer(new JdbcMessageConsumeRepository(db), new JdbcIssuedTicketRepository(db),
                orderService, new OrderStateMachine(orderRepo), risk, mock(InventoryService.class), svc, workflow, metrics,
                (PlatformTransactionManager) tx.getTransactionManager(), outbox);
        assertThrows(RuntimeException.class, () ->
            c.consume("AUTO_PAYMENT:o-fail", "o-fail", "AUTO_PAYMENT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record", Integer.class));
        assertEquals("WAITING_PAYMENT", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='o-fail'", String.class));
    }

    @Test
    void approveConfirmsPayment() {
        seedOrder("o-appr", "RISK_REVIEW");
        jdbc.update("INSERT INTO tb_manual_review_ticket(id,order_id,risk_decision_id,phase,status,version,created_at) "
                + "VALUES('t1','o-appr','rd-appr','RESERVE','PENDING',0,CURRENT_TIMESTAMP)");
        Map<String, Object> ticket = tx.execute(s -> reviews.decide("t1", "req-appr", "APPROVE", 9L));
        assertEquals("WAITING_PAYMENT", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='o-appr'", String.class));
        autoPayment.attempt(ticket.get("order_id").toString());
        verify(executorSpy, times(1)).execute("o-appr");
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='o-appr'", String.class));
    }

    @Test
    void executorOpensActualTransaction() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        final boolean[] sawTx = {false};
        OrderService mockOrders = mock(OrderService.class);
        when(mockOrders.lock(anyString())).thenAnswer(inv -> {
            sawTx[0] = TransactionSynchronizationManager.isActualTransactionActive();
            TicketOrder order = new TicketOrder();
            order.setId("oid");
            order.setOrderNo("oid");
            order.setUserId(1L);
            order.setStatus("WAITING_PAYMENT");
            order.setAmount(BigDecimal.TEN);
            order.setVersion(0);
            order.setDeviceId("d");
            order.setExpiresAt(new Timestamp(System.currentTimeMillis() + 60000));
            return order;
        });
        OrderStateMachine machine = mock(OrderStateMachine.class);
        JdbcPaymentRepository payments = mock(JdbcPaymentRepository.class);
        when(payments.findByPaymentRequestId(anyString())).thenReturn(null);
        when(payments.findById(anyString())).thenReturn(Collections.singletonMap("status", "SUCCESS"));
        OutboxService box = mock(OutboxService.class);
        WorkflowExecutionService wf = mock(WorkflowExecutionService.class);

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(DataSource.class, () -> ds);
        ctx.registerBean(OrderService.class, () -> mockOrders);
        ctx.registerBean(OrderStateMachine.class, () -> machine);
        ctx.registerBean(com.ticketing.repository.PaymentRepository.class, () -> payments);
        ctx.registerBean(OutboxService.class, () -> box);
        ctx.registerBean(WorkflowExecutionService.class, () -> wf);
        ctx.registerBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new);
        ctx.register(TxTestConfig.class);
        ctx.refresh();
        try {
            AutoPaymentExecutor proxied = ctx.getBean(AutoPaymentExecutor.class);
            Map<String, Object> result = proxied.execute("oid");
            assertEquals("SUCCESS", result.get("status"));
            assertTrue(sawTx[0]);
            verify(machine).transition(any(TicketOrder.class), eq(OrderStatus.PAYING));
            verify(machine).transition(any(TicketOrder.class), eq(OrderStatus.PAID));
            verify(box).append("oid", "ISSUE");
        } finally {
            ctx.close();
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TxTestConfig {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AutoPaymentExecutor autoPaymentExecutor(OrderService orders, OrderStateMachine machine,
                                                com.ticketing.repository.PaymentRepository payments,
                                                OutboxService outbox, WorkflowExecutionService workflow,
                                                io.micrometer.core.instrument.MeterRegistry metrics) {
            return new AutoPaymentExecutor(orders, machine, payments, outbox, workflow, metrics);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }
}
