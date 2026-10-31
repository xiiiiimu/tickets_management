package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
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
 * DB local-transaction invariants for ReservationTransactionService.
 * Redis is mocked so failures exercise MySQL/H2 transactional rollback only
 * (stock / quota / reservation / order / outbox), not Lua compensation.
 */
class ReservationTransactionRollbackTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    Db db;
    ReservationTransactionService service;
    InventoryService inventory;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db = new Db(jdbc, new ObjectMapper());
        createSchema();
        inventory = mock(InventoryService.class);
        when(inventory.reserve(any(ReservationIntent.class))).thenReturn(1L);
        WorkflowExecutionService workflow = mock(WorkflowExecutionService.class);
        service = new ReservationTransactionService(
                new JdbcReservationIntentRepository(db),
                new JdbcTicketInventoryRepository(db),
                new JdbcUserPurchaseRepository(db),
                new JdbcReservationRepository(db),
                new JdbcOrderRepository(db),
                inventory,
                new OutboxService(db, new JdbcOutboxRepository(db)),
                workflow,
                new SimpleMeterRegistry(), mock(com.ticketing.risk.RiskDecisionService.class));
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

    private void seedIntent(String requestId, String reservationId, String orderId, String skuId, long user, int qty) {
        jdbc.update(
                "INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,"
                        + "quantity,device_id,ip,trace_id,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                requestId, "fp", reservationId, orderId, user, skuId, qty, "device", "127.0.0.1", "trace", "REQUESTED");
    }

    private int remaining(String sku) {
        return jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?", Integer.class, sku);
    }

    @Test
    void quotaFailureAfterStockCasRollsBackEntireLocalTransaction() {
        String sku = "sku-rb";
        seedSku(sku, 10, 1);
        // quantity 2 exceeds purchase_limit 1 after stock CAS succeeds
        seedIntent("req-rb", "res-rb", "ord-rb", sku, 1L, 2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tx.execute(s -> {
                    service.reserve("req-rb", "session-1");
                    return null;
                }));
        assertTrue(ex.getMessage().contains("限购") || ex.getMessage().contains("超过"));

        assertEquals(10, remaining(sku), "stock CAS must roll back with the transaction");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_purchase", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message", Integer.class));
        assertEquals("REQUESTED",
                jdbc.queryForObject("SELECT status FROM tb_reserve_request WHERE request_id='req-rb'", String.class));
        verify(inventory, times(1)).reserve(any(ReservationIntent.class));
    }

    @Test
    void successfulReservePersistsReservationOrderAndOutboxTogether() {
        String sku = "sku-ok";
        seedSku(sku, 10, 5);
        seedIntent("req-ok", "res-ok", "ord-ok", sku, 1L, 1);

        Map<String, Object> result = tx.execute(s -> service.reserve("req-ok", "session-1"));
        assertNotNull(result);
        assertEquals("res-ok", result.get("reservationId"));
        assertEquals("ord-ok", result.get("orderId"));
        assertEquals("WAITING_PAYMENT", result.get("status"));

        assertEquals(9, remaining(sku));
        assertEquals(1, jdbc.queryForObject("SELECT quantity FROM tb_user_purchase WHERE user_id=1 AND sku_id=?", Integer.class, sku));
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM tb_reservation WHERE id='res-ok'", String.class));
        assertEquals("WAITING_PAYMENT", jdbc.queryForObject("SELECT status FROM tb_ticket_order WHERE id='ord-ok'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE aggregate_id='ord-ok' AND message_key='RISK_CHECK:ord-ok'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE aggregate_id='ord-ok' AND message_key='AUTO_PAYMENT:ord-ok'", Integer.class));
        assertEquals("COMPLETED",
                jdbc.queryForObject("SELECT status FROM tb_reserve_request WHERE request_id='req-ok'", String.class));
    }

    @Test
    void midFailureLeavesNoHalfCommittedBusinessRows() {
        String sku = "sku-half";
        seedSku(sku, 5, 1);
        seedIntent("req-half", "res-half", "ord-half", sku, 9L, 2);

        assertThrows(BusinessException.class, () -> tx.execute(s -> {
            service.reserve("req-half", "session-1");
            return null;
        }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message", Integer.class));
        assertEquals(5, remaining(sku));
    }
}
