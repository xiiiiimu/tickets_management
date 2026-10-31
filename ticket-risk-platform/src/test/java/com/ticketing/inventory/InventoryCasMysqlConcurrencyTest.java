package com.ticketing.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.repository.UserPurchaseRepository;
import com.ticketing.repository.jdbc.JdbcTicketInventoryRepository;
import com.ticketing.repository.jdbc.JdbcUserPurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MySQL concurrent CAS for stock and purchase-limit.
 * Skipped automatically when Docker is unavailable ({@code disabledWithoutDocker=true}).
 */
@Testcontainers(disabledWithoutDocker = true)
class InventoryCasMysqlConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(System.getProperty("ticketing.test.mysql-image", "mysql:8.0")).withUrlParam("generateSimpleParameterMetadata", "true").withUrlParam("useSSL", "false").withUrlParam("serverTimezone", "UTC").withUrlParam("useLegacyDatetimeCode", "false").withDatabaseName("ticketing_cas");

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    TicketInventoryRepository skus;
    UserPurchaseRepository purchases;

    @BeforeEach
    void setup() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Db db = new Db(jdbc, new ObjectMapper());
        skus = new JdbcTicketInventoryRepository(db);
        purchases = new JdbcUserPurchaseRepository(db);
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_ticket_sku("
                + "id VARCHAR(36) PRIMARY KEY,event_session_id VARCHAR(36),name VARCHAR(80),price DECIMAL(18,2),"
                + "total_stock INT,remaining_stock INT,sale_start_time TIMESTAMP,sale_end_time TIMESTAMP,"
                + "purchase_limit INT,status VARCHAR(20),version INT DEFAULT 0,"
                + "CHECK (remaining_stock >= 0), CHECK (total_stock >= remaining_stock), CHECK (purchase_limit > 0))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_purchase("
                + "user_id BIGINT NOT NULL,sku_id VARCHAR(36) NOT NULL,quantity INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(user_id,sku_id),CHECK(quantity>=0))");
        jdbc.update("DELETE FROM tb_user_purchase");
        jdbc.update("DELETE FROM tb_ticket_sku");
    }

    private void seedSku(String id, int stock, int purchaseLimit) {
        jdbc.update(
                "INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,"
                        + "sale_start_time,sale_end_time,purchase_limit,status,version) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, "session-1", "CAS", 10, stock, stock,
                new Timestamp(System.currentTimeMillis() - 1000),
                new Timestamp(System.currentTimeMillis() + 3_600_000),
                purchaseLimit, "ON_SALE", 0);
    }

    @Test
    void concurrentTryDecreaseStockNeverOversells() throws Exception {
        String sku = "mysql-stock";
        int n = 30;
        seedSku(sku, n, 100);
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Void>> jobs = IntStream.range(0, 120).mapToObj(i -> (Callable<Void>) () -> {
                if (Boolean.TRUE.equals(tx.execute(s -> skus.tryDecreaseStock(sku, 1)))) success.incrementAndGet();
                return null;
            }).collect(Collectors.toList());
            for (Future<Void> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdownNow();
        }
        int remaining = jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?", Integer.class, sku);
        assertEquals(n, success.get());
        assertEquals(0, remaining);
        assertTrue(remaining >= 0);
        assertEquals(success.get(), n - remaining);
    }

    @Test
    void concurrentTryIncreaseWithinLimitNeverExceedsPurchaseLimit() throws Exception {
        String sku = "mysql-limit";
        int limit = 5;
        seedSku(sku, 100, limit);
        long user = 7L;
        purchases.ensureZeroRow(user, sku);
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Void>> jobs = IntStream.range(0, 40).mapToObj(i -> (Callable<Void>) () -> {
                if (Boolean.TRUE.equals(tx.execute(s -> purchases.tryIncreaseWithinLimit(user, sku, 1, limit))))
                    success.incrementAndGet();
                return null;
            }).collect(Collectors.toList());
            for (Future<Void> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdownNow();
        }
        int qty = jdbc.queryForObject(
                "SELECT quantity FROM tb_user_purchase WHERE user_id=? AND sku_id=?", Integer.class, user, sku);
        assertEquals(limit, success.get());
        assertEquals(limit, qty);
        assertTrue(qty <= limit);
    }
}
