package com.ticketing.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.ticket.ReservationIntent;
import com.ticketing.ticket.TicketSku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StockBucketServiceTest {
    Db db; StockBucketService buckets; TransactionTemplate tx; TicketSku sku;
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR PRIMARY KEY,total_stock INT,remaining_stock INT,status VARCHAR,sale_start_time TIMESTAMP,sale_end_time TIMESTAMP)");
        db.jdbc.execute("CREATE TABLE tb_reservation(id VARCHAR PRIMARY KEY,sku_id VARCHAR)");
        db.jdbc.execute("CREATE TABLE tb_ticket_stock_bucket(sku_id VARCHAR,bucket_id INT,total_stock INT,remaining_stock INT,version INT DEFAULT 0,PRIMARY KEY(sku_id,bucket_id),CHECK(remaining_stock>=0),CHECK(remaining_stock<=total_stock))");
        db.jdbc.execute("CREATE TABLE tb_stock_bucket_allocation(reservation_id VARCHAR PRIMARY KEY,request_id VARCHAR UNIQUE,sku_id VARCHAR,bucket_id INT,quantity INT,released BOOLEAN)");
        db.jdbc.update("INSERT INTO tb_ticket_sku VALUES('s',16,16,'ON_SALE',TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2030-01-01 00:00:00')");
        for(int i=0;i<16;i++)db.jdbc.update("INSERT INTO tb_ticket_stock_bucket VALUES('s',?,1,1,0)",i);
        sku=new TicketSku();sku.setId("s");sku.setTotalStock(16);sku.setRemainingStock(16);
        buckets=new StockBucketService(db,16);buckets.configure(sku);
    }
    ReservationIntent request(int i) {
        ReservationIntent r=new ReservationIntent();r.setSkuId("s");r.setQuantity(1);
        r.setInventoryBucket(i%16);r.setRequestId("req-"+i);r.setReservationId("res-"+i);return r;
    }
    @Test void concurrentRequestsCannotOversellAndAllocationsMatchDeductions() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(32);
        try {
            List<Future<Boolean>> work=new ArrayList<>();
            for(int i=0;i<160;i++){final int n=i;work.add(pool.submit(()->tx.execute(s->buckets.decrease(request(n)))));}
            int succeeded=0;for(Future<Boolean> f:work)if(f.get(15,TimeUnit.SECONDS))succeeded++;
            assertEquals(16,succeeded);assertEquals(0,buckets.remaining("s"));
            assertEquals(16,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation",Integer.class));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_stock_bucket WHERE remaining_stock<0",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void rollbackRestoresSqlBucketAndDoesNotLeaveAllocation() {
        assertThrows(IllegalStateException.class,()->tx.execute(s->{assertTrue(buckets.decrease(request(0)));throw new IllegalStateException("fail after decrease");}));
        assertEquals(16,buckets.remaining("s"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation",Integer.class));
    }
    @Test void duplicateRestoreAddsStockExactlyOnceAndProjectionIsConsistent() {
        tx.executeWithoutResult(s->assertTrue(buckets.decrease(request(0))));
        buckets.configure(sku);assertEquals(15,db.jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku",Integer.class));
        tx.executeWithoutResult(s->{assertTrue(buckets.restore("res-0"));assertTrue(buckets.restore("res-0"));});
        assertEquals(16,buckets.remaining("s"));buckets.configure(sku);
        assertEquals(16,db.jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku",Integer.class));
    }
    @Test void defaultDisabledPathDoesNotRequireBucketTables() {
        StockBucketService disabled=new StockBucketService(db,1);
        db.jdbc.execute("DROP TABLE tb_ticket_stock_bucket");
        assertTrue(disabled.configure(sku).isEmpty());assertFalse(disabled.active("s"));assertFalse(disabled.restore("missing"));
    }
}
