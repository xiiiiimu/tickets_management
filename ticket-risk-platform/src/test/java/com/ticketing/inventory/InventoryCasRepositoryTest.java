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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository-level MySQL stock / purchase-limit CAS invariants.
 * Uses H2 MODE=MySQL like WalletConcurrencyTest for sequential affected-rows semantics.
 * Concurrent CAS against H2 is intentionally limited — real multi-engine concurrency is covered by
 * {@link InventoryCasMysqlConcurrencyTest} when Docker is available.
 */
class InventoryCasRepositoryTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    TicketInventoryRepository skus;
    UserPurchaseRepository purchases;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Db db = new Db(jdbc, new ObjectMapper());
        skus = new JdbcTicketInventoryRepository(db);
        purchases = new JdbcUserPurchaseRepository(db);
        jdbc.execute("CREATE TABLE tb_ticket_sku("
                + "id VARCHAR PRIMARY KEY,event_session_id VARCHAR,name VARCHAR,price DECIMAL(18,2),"
                + "total_stock INT,remaining_stock INT,sale_start_time TIMESTAMP,sale_end_time TIMESTAMP,"
                + "purchase_limit INT,status VARCHAR,version INT,"
                + "CHECK(remaining_stock>=0),CHECK(total_stock>=remaining_stock),CHECK(purchase_limit>0))");
        jdbc.execute("CREATE TABLE tb_user_purchase("
                + "user_id BIGINT,sku_id VARCHAR,quantity INT DEFAULT 0,"
                + "PRIMARY KEY(user_id,sku_id),CHECK(quantity>=0))");
    }

    private void seedSku(String id, int stock, int purchaseLimit) {
        jdbc.update(
                "INSERT INTO tb_ticket_sku VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, "session-1", "CAS", 10, stock, stock,
                new Timestamp(System.currentTimeMillis() - 1000),
                new Timestamp(System.currentTimeMillis() + 3_600_000),
                purchaseLimit, "ON_SALE", 0);
    }

    private int remaining(String skuId) {
        return jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?", Integer.class, skuId);
    }

    private int purchaseQty(long userId, String skuId) {
        Integer q = jdbc.queryForObject(
                "SELECT quantity FROM tb_user_purchase WHERE user_id=? AND sku_id=?", Integer.class, userId, skuId);
        return q == null ? 0 : q;
    }

    @Test
    void stockCasRejectsOversellSequentiallyAndKeepsNonNegative() {
        String sku = "sku-stock";
        int n = 5;
        seedSku(sku, n, 10);

        int success = 0;
        for (int i = 0; i < n + 10; i++) {
            if (Boolean.TRUE.equals(tx.execute(s -> skus.tryDecreaseStock(sku, 1)))) success++;
        }

        assertEquals(n, success);
        assertEquals(0, remaining(sku));
        assertTrue(remaining(sku) >= 0);
        assertFalse(skus.tryDecreaseStock(sku, 1));
    }

    @Test
    void stockCasMultiQtyMatchesAffectedRowsToDeduction() {
        String sku = "sku-multi";
        seedSku(sku, 10, 10);

        assertEquals(Boolean.TRUE, tx.execute(s -> skus.tryDecreaseStock(sku, 3)));
        assertEquals(7, remaining(sku));
        assertEquals(Boolean.FALSE, tx.execute(s -> skus.tryDecreaseStock(sku, 8)));
        assertEquals(7, remaining(sku));
        assertEquals(Boolean.TRUE, tx.execute(s -> skus.tryDecreaseStock(sku, 7)));
        assertEquals(0, remaining(sku));
    }

    @Test
    void purchaseLimitCasBlocksOverLimitAndKeepsQuantityBounded() {
        String sku = "sku-limit";
        seedSku(sku, 100, 3);
        long user = 42L;
        purchases.ensureZeroRow(user, sku);

        assertEquals(Boolean.TRUE, tx.execute(s -> purchases.tryIncreaseWithinLimit(user, sku, 2, 3)));
        assertEquals(2, purchaseQty(user, sku));

        assertEquals(Boolean.FALSE, tx.execute(s -> purchases.tryIncreaseWithinLimit(user, sku, 2, 3)));
        assertEquals(2, purchaseQty(user, sku));

        assertEquals(Boolean.TRUE, tx.execute(s -> purchases.tryIncreaseWithinLimit(user, sku, 1, 3)));
        assertEquals(3, purchaseQty(user, sku));

        assertEquals(Boolean.FALSE, tx.execute(s -> purchases.tryIncreaseWithinLimit(user, sku, 1, 3)));
        assertEquals(3, purchaseQty(user, sku));
        assertTrue(purchaseQty(user, sku) <= 3);
    }

    /**
     * Concurrent CAS on H2 is a best-effort smoke (same harness as WalletConcurrencyTest).
     * Engine-level isolation guarantees are asserted against MySQL in InventoryCasMysqlConcurrencyTest.
     */
    @Test
    void concurrentStockCasDoesNotOversellOnSharedDatasource() throws Exception {
        String sku = "sku-race";
        int n = 20;
        seedSku(sku, n, 100);
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Void>> jobs = IntStream.range(0, 80).mapToObj(i -> (Callable<Void>) () -> {
                Boolean ok = tx.execute(s -> skus.tryDecreaseStock(sku, 1));
                if (Boolean.TRUE.equals(ok)) success.incrementAndGet();
                return null;
            }).collect(Collectors.toList());
            for (Future<Void> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(n, success.get());
        assertEquals(0, remaining(sku));
        assertEquals(n, n - remaining(sku));
    }
}
