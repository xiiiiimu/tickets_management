package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.jdbc.*;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * requestId / COMPLETED idempotency and HELD→RELEASED MySQL release idempotency.
 * Redis Lua release is out of scope here — OrderService.release restores MySQL stock/quota
 * behind tryReleaseHeld CAS; InventoryService Lua is a separate layer (see TicketPlatformIT).
 */
class ReservationIdempotencyAndReleaseTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    Db db;
    ReservationIntentService intents;
    ReservationTransactionService reservations;
    OrderService orders;
    InventoryService inventory;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db = new Db(jdbc, new ObjectMapper());
        createSchema();
        JdbcReservationIntentRepository intentRepo = new JdbcReservationIntentRepository(db);
        JdbcTicketInventoryRepository skuRepo = new JdbcTicketInventoryRepository(db);
        JdbcUserPurchaseRepository purchaseRepo = new JdbcUserPurchaseRepository(db);
        JdbcReservationRepository reservationRepo = new JdbcReservationRepository(db);
        JdbcOrderRepository orderRepo = new JdbcOrderRepository(db);
        OutboxService outbox = new OutboxService(db, new JdbcOutboxRepository(db));
        WorkflowExecutionService workflow = mock(WorkflowExecutionService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        inventory = mock(InventoryService.class);
        when(inventory.reserve(any(ReservationIntent.class))).thenReturn(1L);
        intents = new ReservationIntentService(db, intentRepo, skuRepo);
        reservations = new ReservationTransactionService(intentRepo, skuRepo, purchaseRepo, reservationRepo, orderRepo,
                inventory, outbox, workflow, metrics, mock(com.ticketing.risk.RiskDecisionService.class));
        OrderStateMachine machine = new OrderStateMachine(orderRepo);
        orders = new OrderService(orderRepo, reservationRepo, skuRepo, purchaseRepo, machine, outbox, workflow, metrics);
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR PRIMARY KEY,event_session_id VARCHAR,name VARCHAR,"
                + "price DECIMAL(18,2),total_stock INT,remaining_stock INT,sale_start_time TIMESTAMP,"
                + "sale_end_time TIMESTAMP,purchase_limit INT,status VARCHAR,version INT,"
                + "CHECK(remaining_stock>=0),CHECK(purchase_limit>0))");
        jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR PRIMARY KEY,fingerprint VARCHAR,"
                + "reservation_id VARCHAR UNIQUE,order_id VARCHAR UNIQUE,user_id BIGINT,sku_id VARCHAR,"
                + "quantity INT,device_id VARCHAR,ip VARCHAR,trace_id VARCHAR,status VARCHAR,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_user_purchase(user_id BIGINT,sku_id VARCHAR,quantity INT DEFAULT 0,"
                + "PRIMARY KEY(user_id,sku_id),CHECK(quantity>=0))");
        jdbc.execute("CREATE TABLE tb_reservation(id VARCHAR PRIMARY KEY,request_id VARCHAR UNIQUE,user_id BIGINT,"
                + "sku_id VARCHAR,quantity INT,status VARCHAR,expires_at TIMESTAMP,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,order_no VARCHAR UNIQUE,"
                + "reservation_id VARCHAR UNIQUE,user_id BIGINT,sku_id VARCHAR,quantity INT,amount DECIMAL(18,2),"
                + "device_id VARCHAR,ip VARCHAR,status VARCHAR,version INT,trace_id VARCHAR,expires_at TIMESTAMP,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_outbox_message(id VARCHAR PRIMARY KEY,aggregate_type VARCHAR,aggregate_id VARCHAR,"
                + "topic VARCHAR,message_key VARCHAR UNIQUE,payload VARCHAR,status VARCHAR,"
                + "retry_count INT DEFAULT 0,next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "lease_token VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    private void seedSku(String id, int stock, int limit) {
        jdbc.update("INSERT INTO tb_ticket_sku VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, "session-1", "T", new BigDecimal("10.00"), stock, stock,
                new Timestamp(System.currentTimeMillis() - 1000),
                new Timestamp(System.currentTimeMillis() + 3_600_000),
                limit, "ON_SALE", 0);
    }

    private ReserveRequest request(String requestId, String sku, long user) {
        ReserveRequest r = new ReserveRequest();
        r.setRequestId(requestId);
        r.setUserId(user);
        r.setTicketSkuId(sku);
        r.setEventSessionId("session-1");
        r.setQuantity(1);
        r.setDeviceId("device-" + user);
        return r;
    }

    private int remaining(String sku) {
        return jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?", Integer.class, sku);
    }

    @Test
    void concurrentDistinctRequestIdsDoNotRequireSkuRowLockSerialization() throws Exception {
        String sku = "sku-parallel-intent";
        seedSku(sku, 50, 5);
        int n = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> tx.execute(s -> {
                    ReserveRequest r = request("req-par-" + idx, sku, idx + 1L);
                    return intents.create(r, "127.0.0.1").getRequestId();
                })));
            }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (java.util.concurrent.Future<String> f : futures) {
                ids.add(f.get());
            }
            assertEquals(n, ids.size());
            assertEquals(n, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?", Integer.class, sku));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameRequestIdCreateIsIdempotentAndDoesNotDuplicateIntentIds() {
        String sku = "sku-intent";
        seedSku(sku, 10, 5);
        ReserveRequest r = request("idem-intent", sku, 1L);

        ReservationIntent first = tx.execute(s -> intents.create(r, "127.0.0.1"));
        ReservationIntent second = tx.execute(s -> intents.create(r, "127.0.0.1"));

        assertEquals(first.getReservationId(), second.getReservationId());
        assertEquals(first.getOrderId(), second.getOrderId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE request_id='idem-intent'", Integer.class));
    }

    @Test
    void completedReserveReplayDoesNotDoubleDebitMysqlStockOrCreateSecondOrder() {
        String sku = "sku-replay";
        seedSku(sku, 10, 5);
        ReserveRequest r = request("idem-reserve", sku, 1L);
        tx.execute(s -> {
            intents.create(r, "127.0.0.1");
            return null;
        });

        Map<String, Object> first = tx.execute(s -> reservations.reserve("idem-reserve", "session-1"));
        assertEquals(9, remaining(sku));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
        // Only AUTO_PAYMENT is published at reserve; risk waits for completed issue.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_message WHERE message_key LIKE 'AUTO_PAYMENT:%'", Integer.class));

        Map<String, Object> second = tx.execute(s -> reservations.reserve("idem-reserve", "session-1"));
        assertEquals(first.get("reservationId"), second.get("reservationId"));
        assertEquals(first.get("orderId"), second.get("orderId"));
        assertEquals(9, remaining(sku), "COMPLETED replay must not decrease stock again");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message", Integer.class));
        // Redis reserve is invoked only on the first non-COMPLETED path
        verify(inventory, times(1)).reserve(any(ReservationIntent.class));
    }

    @Test
    void heldToReleasedMysqlReleaseIsIdempotentAndRestoresStockOnce() {
        String sku = "sku-rel";
        seedSku(sku, 5, 5);
        String reservationId = "res-rel";
        String orderId = "ord-rel";
        jdbc.update("INSERT INTO tb_user_purchase VALUES(1,?,1)", sku);
        jdbc.update("INSERT INTO tb_reservation VALUES(?,?,1,?,1,'HELD',?,CURRENT_TIMESTAMP)",
                reservationId, "req-rel", sku, new Timestamp(System.currentTimeMillis() + 600_000));
        jdbc.update("INSERT INTO tb_ticket_order VALUES(?,?,?,1,?,1,10,'device','ip','CREATED',0,'trace',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                orderId, orderId, reservationId, sku, new Timestamp(System.currentTimeMillis() + 600_000));

        TicketOrder order = new TicketOrder();
        order.setId(orderId);
        order.setReservationId(reservationId);
        order.setSkuId(sku);
        order.setUserId(1L);
        order.setQuantity(1);

        tx.execute(s -> {
            orders.release(order);
            return null;
        });
        assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM tb_reservation WHERE id=?", String.class, reservationId));
        assertEquals(6, remaining(sku));
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM tb_user_purchase WHERE user_id=1 AND sku_id=?", Integer.class, sku));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key=?", Integer.class, "RELEASE:" + orderId));

        tx.execute(s -> {
            orders.release(order);
            return null;
        });
        assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM tb_reservation WHERE id=?", String.class, reservationId));
        assertEquals(6, remaining(sku), "second release must not restore stock again");
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM tb_user_purchase WHERE user_id=1 AND sku_id=?", Integer.class, sku));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key=?", Integer.class, "RELEASE:" + orderId));
    }
}
