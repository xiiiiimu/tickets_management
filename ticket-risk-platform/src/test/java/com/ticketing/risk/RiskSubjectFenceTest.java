package com.ticketing.risk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskSubjectFenceTest {
    Db db;RiskSubjectFence fence;TransactionTemplate tx;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:fence-"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.jdbc.execute("CREATE TABLE tb_risk_subject_guard(subject_type VARCHAR,subject_value VARCHAR,blocked_until TIMESTAMP,version BIGINT DEFAULT 0,PRIMARY KEY(subject_type,subject_value))");
        db.jdbc.execute("CREATE TABLE tb_risk_profile(subject_type VARCHAR,subject_value VARCHAR,decision VARCHAR,expires_at TIMESTAMP,PRIMARY KEY(subject_type,subject_value))");
        fence=new RiskSubjectFence(db,mock(StringRedisTemplate.class));ReflectionTestUtils.setField(fence,"enabled",true);
        tx.executeWithoutResult(s->fence.prepare(2,"d","ip"));
    }
    @Test void profileBlockAndDatabaseBlacklistAreEnforcedEvenWithoutRedisPortrait(){
        assertEquals(Boolean.TRUE,tx.execute(s->fence.lockAndCheck(2,"d","ip")));
        tx.executeWithoutResult(s->{fence.exclusive(2,"d","ip");db.jdbc.update("INSERT INTO tb_risk_profile VALUES('USER','2','BLOCK',?)",new Timestamp(System.currentTimeMillis()+60000));});
        assertEquals(Boolean.FALSE,tx.execute(s->fence.lockAndCheck(2,"d","ip")));
        db.jdbc.update("DELETE FROM tb_risk_profile");db.jdbc.update("UPDATE tb_risk_subject_guard SET blocked_until=? WHERE subject_type='IP'",new Timestamp(System.currentTimeMillis()+60000));
        assertEquals(Boolean.FALSE,tx.execute(s->fence.lockAndCheck(2,"d","ip")));
    }
    @Test void newBlockCannotCrossAnAlreadyStartedGrantTransaction()throws Exception{
        CountDownLatch checked=new CountDownLatch(1),commit=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> grant=pool.submit(()->tx.executeWithoutResult(s->{assertTrue(fence.lockAndCheck(2,"d","ip"));checked.countDown();try{assertTrue(commit.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertTrue(checked.await(3,TimeUnit.SECONDS));
            Future<?> update=pool.submit(()->tx.executeWithoutResult(s->{fence.exclusive(2,"d","ip");db.jdbc.update("INSERT INTO tb_risk_profile VALUES('IP','ip','BLOCK',?)",new Timestamp(System.currentTimeMillis()+60000));}));
            Thread.sleep(100);assertFalse(update.isDone());commit.countDown();grant.get(3,TimeUnit.SECONDS);update.get(3,TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE,tx.execute(s->fence.lockAndCheck(2,"d","ip")));
        }finally{commit.countDown();pool.shutdownNow();}
    }
}
