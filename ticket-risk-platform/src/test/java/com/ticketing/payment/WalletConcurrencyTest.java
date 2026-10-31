package com.ticketing.payment;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.repository.jdbc.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

class WalletConcurrencyTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    WalletService wallet;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,reservation_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),status VARCHAR,version INT,trace_id VARCHAR,expires_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_wallet_account(user_id BIGINT PRIMARY KEY,available_balance DECIMAL(18,2) CHECK(available_balance>=0),version INT)");
        jdbc.execute("CREATE TABLE tb_payment_transaction(id VARCHAR PRIMARY KEY,payment_request_id VARCHAR UNIQUE,order_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),type VARCHAR,status VARCHAR,success_key VARCHAR UNIQUE,device_id VARCHAR)");
        jdbc.execute("CREATE TABLE tb_wallet_ledger(id VARCHAR PRIMARY KEY,user_id BIGINT,order_id VARCHAR,transaction_id VARCHAR UNIQUE,type VARCHAR,amount DECIMAL(18,2))");
        jdbc.update("INSERT INTO tb_wallet_account VALUES(1,100,0)");
        for (int i = 0; i < 3; i++)
            jdbc.update("INSERT INTO tb_ticket_order VALUES(?,?,1,80,'WAITING_PAYMENT',0,'trace',?,CURRENT_TIMESTAMP)", "order" + i, "r" + i, new java.sql.Timestamp(System.currentTimeMillis() + 600000));
        Db db = new Db(jdbc, new ObjectMapper());
        JdbcOrderRepository orderRepo = new JdbcOrderRepository(db);
        OrderStateMachine machine = new OrderStateMachine(orderRepo);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowExecutionService workflow = mock(WorkflowExecutionService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        OrderService orders = new OrderService(orderRepo, new JdbcReservationRepository(db), new JdbcTicketInventoryRepository(db),
                new JdbcUserPurchaseRepository(db), machine, outbox, workflow, metrics);
        RiskDecisionService risk = mock(RiskDecisionService.class);
        doNothing().when(risk).fast(anyLong(), anyString(), anyString());
        wallet = new WalletService(new JdbcWalletRepository(db), new JdbcPaymentRepository(db), new JdbcWalletLedgerRepository(db),
                new JdbcIssuedTicketRepository(db), orders, machine, risk, outbox, workflow, metrics);
    }

    private PaymentRequest request(String id) {
        PaymentRequest r = new PaymentRequest();
        r.setPaymentRequestId(id);
        r.setDeviceId("device");
        return r;
    }

    private List<Map<String, Object>> concurrent(java.util.function.IntFunction<Map<String, Object>> task, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Map<String, Object>>> jobs = IntStream.range(0, n).mapToObj(i -> (Callable<Map<String, Object>>) () -> tx.execute(status -> task.apply(i))).collect(Collectors.toList());
            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> f : pool.invokeAll(jobs)) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameRequestOneDebitAndStableResponse() throws Exception {
        List<Map<String, Object>> results = concurrent(i -> wallet.pay("order0", 1, request("pay1"), "127.0.0.1"), 100);
        assertEquals(1, results.stream().map(r -> r.get("id")).distinct().count());
        assertEquals(20, jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_wallet_ledger", Integer.class));
    }

    @Test
    void differentOrdersCannotOverdraw() throws Exception {
        concurrent(i -> wallet.pay("order" + i, 1, request("p" + i), "127.0.0.1"), 3);
        assertEquals(20, jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE status='SUCCESS'", Integer.class));
    }

    @Test
    void sameIdCannotBeReusedForAnotherOrder() {
        tx.execute(s -> wallet.pay("order0", 1, request("same"), "127.0.0.1"));
        assertThrows(RuntimeException.class, () -> tx.execute(s -> wallet.pay("order1", 1, request("same"), "127.0.0.1")));
        assertEquals(20, jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account", Integer.class));
    }

    @Test
    void failedPaymentIsIdempotent() {
        jdbc.update("UPDATE tb_wallet_account SET available_balance=0");
        Map<String, Object> first = tx.execute(s -> wallet.pay("order0", 1, request("failed"), "127.0.0.1"));
        Map<String, Object> second = tx.execute(s -> wallet.pay("order0", 1, request("failed"), "127.0.0.1"));
        assertEquals("FAILED", first.get("status"));
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(0, jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account", Integer.class));
    }
}
