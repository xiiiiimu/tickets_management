package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real InnoDB tests; the caller must provision a fresh owner-checked test schema. */
@EnabledIfSystemProperty(named="ticketing.attempts.mysql",matches="true")
class ReservationAttemptMysqlTest {
    Db db;ReservationDispatch queue;DataSourceTransactionManager manager;MockEnvironment env;ReservationAttemptStore first;
    AtomicLong now;SimpleMeterRegistry metrics;
    @BeforeAll static void migrateFreshOwnedFixture(){
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));JdbcTemplate jdbc=new JdbcTemplate(source);
        assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V106__reservation_dispatch.sql")).execute(source);
        jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,reservation_id,order_id) VALUES('old-fixture','old-fixture','old-fp',1,'s','sku',1,'d','ip',1,'EVALUATING',?,'old-res','old-order')",new Timestamp(System.currentTimeMillis()+30000));
        Map<String,Object> old=jdbc.queryForMap("SELECT * FROM tb_reservation_job WHERE id='old-fixture'");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(source);
        Map<String,Object> migrated=jdbc.queryForMap("SELECT * FROM tb_reservation_job WHERE id='old-fixture'");for(Map.Entry<String,Object> e:old.entrySet())assertEquals(e.getValue(),migrated.get(e.getKey()));assertEquals(2,((Number)migrated.get("attempt_protocol")).intValue());
        jdbc.update("DELETE FROM tb_reservation_job WHERE id='old-fixture'");
        jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY,status VARCHAR(24))");jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR(36) PRIMARY KEY,status VARCHAR(24))");
        jdbc.update("INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol) VALUES(1,2,512,4,2000,3)");
    }
    @BeforeEach void setup(){
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"));
        DriverManagerDataSource ds=new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),db.jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));
        db.jdbc.update("DELETE FROM tb_reservation_request_alias");db.jdbc.update("DELETE FROM tb_reservation_risk_audit");db.jdbc.update("DELETE FROM tb_reservation_job");
        manager=new DataSourceTransactionManager(ds);metrics=new SimpleMeterRegistry();env=new MockEnvironment().withProperty("spring.datasource.url",url);
        queue=new ReservationDispatch(db,mock(RiskSubjectFence.class),manager);ReflectionTestUtils.setField(queue,"capacity",512);ReflectionTestUtils.setField(queue,"workers",4);ReflectionTestUtils.setField(queue,"passWeight",2);ReflectionTestUtils.setField(queue,"reviewAge",2000L);ReflectionTestUtils.setField(queue,"queueTimeout",30000L);ReflectionTestUtils.setField(queue,"durableAttempts",true);queue.initialize();
        now=new AtomicLong(db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).getTime());first=store(db);
    }
    ReservationAttemptStore store(Db database){ReservationAttemptStore s=new ReservationAttemptStore(database,queue,manager,metrics,env);s.clock(()->new Timestamp(now.get()));s.initialize();return s;}
    Map<String,Object> register(int i){ReserveRequest r=new ReserveRequest();r.setRequestId("mysql-attempt-"+i);r.setUserId((long)i);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setDeviceId("device-"+i);r.setQuantity(1);return first.register(r,"ip-"+i,"fp-"+i,1);}
    @Test void rowLockAcquiredBeforeDeadlineButFreshPostLockTimeAtDeadlineCannotClaim()throws Exception{
        Map<String,Object> job=register(1);long deadline=((Timestamp)job.get("waiting_deadline")).getTime();now.set(deadline-1);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        Db observing=new Db(db.jdbc,new ObjectMapper()){@Override public Map<String,Object> one(String sql,Object...args){
            Map<String,Object> result=super.one(sql,args);
            if(sql.contains("state='WAITING' ORDER BY")&&result!=null){locked.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("release missing");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
            return result;
        }};
        ReservationAttemptStore claimant=store(observing);ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<Map<String,Object>> future=executor.submit(claimant::claim);assertTrue(locked.await(2,TimeUnit.SECONDS));now.set(deadline);first.expireWaiting();release.countDown();assertNull(future.get(5,TimeUnit.SECONDS));
            assertEquals("QUEUE_TIMEOUT",queue.get(job.get("id").toString()).get("state"));assertNull(first.claim());
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void twoInstancesClaimEachAttemptOnceAndNeverExceedFour()throws Exception{
        for(int i=1;i<=8;i++)register(i);ReservationAttemptStore second=store(db);CyclicBarrier go=new CyclicBarrier(8);ExecutorService executor=Executors.newFixedThreadPool(8);
        try{List<Future<Map<String,Object>>> results=new ArrayList<>();for(int i=0;i<8;i++){ReservationAttemptStore target=i%2==0?first:second;results.add(executor.submit(()->{go.await(3,TimeUnit.SECONDS);return target.claim();}));}
            Set<Object> ids=new HashSet<>();for(Future<Map<String,Object>> f:results){Map<String,Object> job=f.get(8,TimeUnit.SECONDS);if(job!=null)assertTrue(ids.add(job.get("id")));}assertEquals(4,ids.size());assertEquals(4,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='PROCESSING'",Integer.class));
        }finally{executor.shutdownNow();}
    }
    @Test void timeoutAndClaimAtExactDeadlineOnlyTimeoutCanWin()throws Exception{
        Map<String,Object> job=register(1);now.addAndGet(5000);ReservationAttemptStore second=store(db);CyclicBarrier go=new CyclicBarrier(2);ExecutorService executor=Executors.newFixedThreadPool(2);
        try{Future<Map<String,Object>> claim=executor.submit(()->{go.await(3,TimeUnit.SECONDS);return first.claim();});Future<?> timeout=executor.submit(()->{go.await(3,TimeUnit.SECONDS);second.expireWaiting();return null;});assertNull(claim.get(5,TimeUnit.SECONDS));timeout.get(5,TimeUnit.SECONDS);assertEquals("QUEUE_TIMEOUT",queue.get(job.get("id").toString()).get("state"));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_risk_audit WHERE result_code='QUEUE_TIMEOUT'",Integer.class));}
        finally{executor.shutdownNow();}
    }
    @Test void expiredProcessingLeaseRecoversSameAttemptAfterThirtySeconds(){
        Map<String,Object> registered=register(1),claimed=first.claim();now.addAndGet(31000);ReservationAttemptStore second=store(db);Map<String,Object> recovered=second.claim();
        assertEquals(registered.get("id"),recovered.get("id"));assertEquals(registered.get("reservation_id"),recovered.get("reservation_id"));assertEquals(registered.get("waiting_deadline"),recovered.get("waiting_deadline"));assertEquals("PROCESSING",recovered.get("state"));assertNotEquals(claimed.get("lease_token"),recovered.get("lease_token"));assertFalse(first.heartbeat(claimed));
    }
    @Test void dispatchHintDoesNotClaimWorkOrExposeHealthyLeases(){register(1);assertEquals(1,first.availableHint());assertEquals("WAITING",queue.byRequest("mysql-attempt-1").get("state"));first.claim();assertEquals(0,first.availableHint());now.addAndGet(15001);assertEquals(1,first.availableHint());}
}
