package com.ticketing.risk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskHistorySummaryTest {
    Db db;RiskHistorySummary summary;TransactionTemplate tx;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:summary-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        db.jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY,create_time TIMESTAMP)");
        db.jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,user_id BIGINT,status VARCHAR)");
        db.jdbc.execute("CREATE TABLE tb_payment_transaction(id VARCHAR PRIMARY KEY,user_id BIGINT,device_id VARCHAR,type VARCHAR,status VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V105__risk_history_summary.sql")).execute(ds);
        db.jdbc.update("INSERT INTO tb_user VALUES(2,TIMESTAMP '2025-01-01 00:00:00')");
        summary=new RiskHistorySummary(db,new ObjectMapper(),manager);ReflectionTestUtils.setField(summary,"enabled",true);
    }
    @Test void sourceMutationAndInvalidationRollBackTogether(){
        summary.read(2,"d");
        tx.executeWithoutResult(s->{db.jdbc.update("INSERT INTO tb_ticket_order VALUES('x',2,'CANCELLED')");summary.changed(2,"order:x:1");s.setRollbackOnly();});
        assertEquals(0d,summary.read(2,"d").get("historyCount"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_summary_event",Integer.class));
        assertThrows(IllegalStateException.class,()->summary.changed(2,"outside-tx"));
    }
    @Test void committedEventsRefreshWithoutChangingLifetimeAndLastThirtyPaymentSemantics(){
        assertEquals(0d,summary.read(2,"d").get("historyCount"));
        tx.executeWithoutResult(s->{
            db.jdbc.update("INSERT INTO tb_ticket_order VALUES('a',2,'CANCELLED'),('b',2,'ISSUED'),('c',2,'REFUNDED')");
            db.jdbc.update("INSERT INTO tb_payment_transaction(id,user_id,device_id,type,status) VALUES('a',2,'d','PAYMENT','SUCCESS'),('b',2,'e','PAYMENT','FAILED'),('c',2,'e','PAYMENT','FAILED')");
            summary.changed(2,"fixture");summary.changed(2,"fixture");
        });
        Map<String,Double> f=summary.read(2,"d");
        assertEquals(3d,f.get("historyCount"));assertEquals(1d/3,f.get("cancelRate"));assertEquals(1d/3,f.get("refundRate"));
        assertEquals(2d,f.get("paymentFailures"));assertEquals(0d,f.get("newDevice"));assertEquals(1d,summary.read(2,"unknown").get("newDevice"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_summary_event",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_summary_event WHERE processed=false",Integer.class));
    }
    @Test void cacheExpiryRebuildsInsteadOfReturningInventedZeroRisk(){
        summary.read(2,"d");db.jdbc.update("UPDATE tb_risk_user_summary SET captured_at=TIMESTAMP '2020-01-01 00:00:00'");
        db.jdbc.update("INSERT INTO tb_ticket_order VALUES('late',2,'REFUNDED')");
        assertEquals(1d,summary.read(2,"d").get("refundRate"));
        db.jdbc.execute("DROP TABLE tb_ticket_order");db.jdbc.update("DELETE FROM tb_risk_user_summary");
        assertThrows(RuntimeException.class,()->summary.read(2,"d"));
    }
}
