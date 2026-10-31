package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Batch registration and batch claim on real InnoDB (savepoints, SKIP LOCKED, concurrent stores).
 * Same opt-in and owned-schema guard as ReservationAttemptMysqlTest: -Dticketing.attempts.mysql=true and an
 * explicitly owned credit_risk_controlled_* schema that contains only tb_controlled_owner.
 */
@EnabledIfSystemProperty(named="ticketing.attempts.mysql",matches="true")
class AttemptThroughputMysqlTest {
    static String url;Db db;DataSourceTransactionManager manager;ReservationDispatch queue;SimpleMeterRegistry metrics;MockEnvironment env;
    @BeforeAll static void migrateFreshOwnedFixture(){
        url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));JdbcTemplate jdbc=new JdbcTemplate(source);
        assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new org.springframework.core.io.ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(source);
        jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY,status VARCHAR(24))");jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR(36) PRIMARY KEY,status VARCHAR(24))");
        jdbc.update("INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol) VALUES(1,2,512,4,2000,3)");
    }
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),db.jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));
        db.jdbc.update("DELETE FROM tb_reservation_request_alias");db.jdbc.update("DELETE FROM tb_reservation_risk_audit");db.jdbc.update("DELETE FROM tb_reservation_job");
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=512,active_limit=4 WHERE id=1");
        manager=new DataSourceTransactionManager(ds);metrics=new SimpleMeterRegistry();env=new MockEnvironment().withProperty("spring.datasource.url",url);
        queue=new ReservationDispatch(db,mock(RiskSubjectFence.class),manager);ReflectionTestUtils.setField(queue,"capacity",512);ReflectionTestUtils.setField(queue,"workers",4);ReflectionTestUtils.setField(queue,"passWeight",2);ReflectionTestUtils.setField(queue,"reviewAge",2000L);ReflectionTestUtils.setField(queue,"queueTimeout",30000L);ReflectionTestUtils.setField(queue,"durableAttempts",true);queue.initialize();
    }
    ReservationAttemptStore store(){ReservationAttemptStore s=new ReservationAttemptStore(db,queue,manager,metrics,env);s.initialize();return s;}
    static ReservationAttemptStore.Registration item(int user,String id,String fp){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId((long)user);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setDeviceId("device-"+user);r.setQuantity(1);return new ReservationAttemptStore.Registration(r,"ip-"+user,fp,1);}
    int count(String where){return db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE "+where,Integer.class);}

    @Test void savepointsIsolateRefusalsOnInnoDb(){
        ReservationAttemptStore store=store();
        store.registerBatch(Collections.singletonList(item(1,"seed","fp-seed")));
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=4 WHERE id=1");
        List<ReservationAttemptStore.Registration> items=Arrays.asList(item(2,"a","fp-a"),item(1,"seed","fp-other"),item(3,"b","fp-b"),item(3,"b-again","fp-b"),item(4,"c","fp-c"),item(5,"d","fp-d"));
        store.registerBatch(items);
        assertNull(items.get(0).failure());
        assertEquals(409,((BusinessException)items.get(1).failure()).status,"fingerprint mismatch of an existing request");
        assertNull(items.get(2).failure());
        assertEquals(items.get(2).result().get("id"),items.get(3).result().get("id"),"second click of same user/session aliases inside the batch");
        assertNull(items.get(4).failure());
        assertEquals(429,((AttemptAdmissionException)items.get(5).failure()).status,"capacity 4 counts rows inserted earlier in the same batch");
        assertEquals(4,count("1=1"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_request_alias WHERE request_id='b-again'",Integer.class));
    }
    @Test void concurrentBatchRegistrarsAndBatchClaimersKeepEveryBound()throws Exception{
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=60,active_limit=4 WHERE id=1");
        ReservationAttemptStore registrarA=store(),registrarB=store(),claimerA=store(),claimerB=store();
        ExecutorService pool=Executors.newFixedThreadPool(4);AtomicBoolean done=new AtomicBoolean();
        Set<String> grantedTokens=ConcurrentHashMap.newKeySet();AtomicInteger grants=new AtomicInteger(),maxActive=new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors=new ConcurrentLinkedQueue<>();
        try{
            List<Future<?>> writers=new ArrayList<>();
            for(ReservationAttemptStore s:Arrays.asList(registrarA,registrarB)){int base=s==registrarA?0:1000;
                writers.add(pool.submit(()->{for(int b=0;b<10;b++){List<ReservationAttemptStore.Registration> items=new ArrayList<>();
                    for(int i=0;i<10;i++){int u=base+b*10+i;items.add(item(u,"c-"+u,"fp-"+u));}
                    try{s.registerBatch(items);}catch(RuntimeException e){errors.add(e);}
                }return null;}));}
            List<Future<?>> claimers=new ArrayList<>();
            for(ReservationAttemptStore s:Arrays.asList(claimerA,claimerB))claimers.add(pool.submit(()->{
                while(!done.get()){
                    try{
                        for(Map<String,Object> j:s.claimBatch(3)){
                            assertTrue(grantedTokens.add(j.get("lease_token").toString()),"a grant token is unique");grants.incrementAndGet();
                            int active=count("state='PROCESSING'");maxActive.accumulateAndGet(active,Math::max);
                            // finish the attempt so the global active slot frees up
                            db.jdbc.update("UPDATE tb_reservation_job SET state='RESERVED',active_owner=NULL,lease_token=NULL WHERE id=? AND lease_token=?",j.get("id"),j.get("lease_token"));
                        }
                    }catch(RuntimeException e){errors.add(e);}
                }return null;}));
            for(Future<?> f:writers)f.get(60,TimeUnit.SECONDS);
            long stop=System.currentTimeMillis()+20000;
            while(count("state IN ('WAITING','PROCESSING')")>0&&System.currentTimeMillis()<stop)Thread.sleep(20);
            done.set(true);for(Future<?> f:claimers)f.get(30,TimeUnit.SECONDS);
        }finally{done.set(true);pool.shutdownNow();}
        System.out.println("DB_ERRORS "+errors.size()+" "+errors.stream().map(e->e.getClass().getSimpleName()).distinct().collect(java.util.stream.Collectors.toList()));
        for(Throwable e:errors)if(!(e instanceof org.springframework.dao.DataAccessException))fail(e);
        int registered=count("1=1");
        assertTrue(registered>0);
        assertEquals(registered,count("state IN ('RESERVED','QUEUE_TIMEOUT')"),"every registered attempt was granted exactly once or timed out");
        assertEquals(count("state='RESERVED'"),grants.get());
        assertTrue(maxActive.get()<=4,"persisted active_limit is global across stores: "+maxActive.get());
        assertEquals(registered,db.jdbc.queryForObject("SELECT COUNT(DISTINCT request_id) FROM tb_reservation_job",Integer.class));
    }
    @Test void twoBatchClaimersNeverGrantTheSameAttempt()throws Exception{
        db.jdbc.update("UPDATE tb_reservation_dispatch SET active_limit=40 WHERE id=1");
        ReservationAttemptStore writer=store();List<ReservationAttemptStore.Registration> items=new ArrayList<>();
        for(int i=0;i<30;i++)items.add(item(i,"p-"+i,"fp-"+i));
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=512 WHERE id=1");
        // waiting budget (initial 2s service estimate) admits 4+(4750/2000)*40 waiting, enough for 30
        writer.registerBatch(items);for(ReservationAttemptStore.Registration r:items)assertNull(r.failure(),String.valueOf(r.failure()));
        ReservationAttemptStore a=store(),b=store();CyclicBarrier go=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<List<Map<String,Object>>> fa=pool.submit(()->{go.await(5,TimeUnit.SECONDS);List<Map<String,Object>> all=new ArrayList<>();for(int i=0;i<5;i++)all.addAll(a.claimBatch(4));return all;});
            Future<List<Map<String,Object>>> fb=pool.submit(()->{go.await(5,TimeUnit.SECONDS);List<Map<String,Object>> all=new ArrayList<>();for(int i=0;i<5;i++)all.addAll(b.claimBatch(4));return all;});
            Set<Object> ids=new HashSet<>();int total=0;
            for(Map<String,Object> j:fa.get(30,TimeUnit.SECONDS)){assertTrue(ids.add(j.get("id")));total++;}
            for(Map<String,Object> j:fb.get(30,TimeUnit.SECONDS)){assertTrue(ids.add(j.get("id")),"granted twice: "+j.get("id"));total++;}
            assertEquals(30,total);assertEquals(30,count("state='PROCESSING'"));
        }finally{pool.shutdownNow();}
    }

    // ---------------- phase 1: registrar cancel/take semantics and coordinator lock timing on InnoDB ----------------
    AttemptRegistrar registrar(ReservationAttemptStore store,long waitMs,long takenMs){
        MockEnvironment e=new MockEnvironment();
        e.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms",Long.toString(waitMs));
        e.setProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms",Long.toString(takenMs));
        e.setProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms","0");
        AttemptRegistrar r=new AttemptRegistrar(store,metrics,e);r.start();return r;
    }
    /** Holds the coordinator row from a separate connection, like another node's long transaction. */
    Future<?> holdCoordinator(ExecutorService pool,CountDownLatch held,long millis){
        return pool.submit(()->{new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(st->{
            db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE");held.countDown();
            try{Thread.sleep(millis);}catch(InterruptedException i){Thread.currentThread().interrupt();}
        });return null;});
    }
    io.micrometer.core.instrument.Timer lockTimer(String op,String phase,String outcome){return metrics.find(AttemptCoordinatorLock.METRIC).tags("operation",op,"phase",phase,"outcome",outcome).timer();}

    @Test void cancelledQueuedRequestNeverCommitsWhileTakenOneWaitsForItsCommit()throws Exception{
        ReservationAttemptStore store=store();AttemptRegistrar reg=registrar(store,200,3000);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try{
            CountDownLatch held=new CountDownLatch(1);Future<?> blocker=holdCoordinator(pool,held,900);assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<Map<String,Object>> taken=pool.submit(()->reg.register(item(1,"taken","fp-1").request,"ip","fp-1",1));
            long until=System.currentTimeMillis()+2000;
            while(metrics.get("attempt.register.batch.inflight.ms").gauge().value()<=0&&System.currentTimeMillis()<until)Thread.sleep(5);
            // registrar thread is now blocked on the coordinator lock inside batch #1 (TAKEN); the next one stays QUEUED
            AttemptAdmissionException cancelled=assertThrows(AttemptAdmissionException.class,()->reg.register(item(2,"cancelled","fp-2").request,"ip","fp-2",1));
            assertEquals(429,cancelled.status);
            Map<String,Object> receipt=taken.get(5,TimeUnit.SECONDS);
            assertEquals("taken",receipt.get("request_id"),"TAKEN request waits past the 200ms wait for its committed receipt");
            blocker.get(5,TimeUnit.SECONDS);Thread.sleep(300);
            assertEquals(1,count("request_id='taken'"));assertEquals(0,count("request_id='cancelled'"),"a cancelled request never reaches MySQL");
            io.micrometer.core.instrument.Timer wait=lockTimer("register_batch","wait","acquired"),hold=lockTimer("register_batch","hold","commit");
            assertNotNull(wait);assertNotNull(hold);
            assertTrue(wait.max(TimeUnit.MILLISECONDS)>=500,"lock wait measured while another transaction held the row: "+wait.max(TimeUnit.MILLISECONDS));
            assertTrue(hold.max(TimeUnit.MILLISECONDS)<wait.max(TimeUnit.MILLISECONDS),"hold excludes the wait");
            System.out.println("LOCK register_batch wait="+wait.max(TimeUnit.MILLISECONDS)+"ms hold="+hold.max(TimeUnit.MILLISECONDS)+"ms");
        }finally{reg.stop();pool.shutdownNow();}
    }
    @Test void takenRequestBlockedPastTheTransactionTimeoutIsNeverAReceiptOrAFalseFailure()throws Exception{
        ReservationAttemptStore store=store();AttemptRegistrar reg=registrar(store,200,6000);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch held=new CountDownLatch(1);Future<?> blocker=holdCoordinator(pool,held,4500);assertTrue(held.await(5,TimeUnit.SECONDS));
            RuntimeException failed=assertThrows(RuntimeException.class,()->reg.register(item(1,"slow","fp-1").request,"ip","fp-1",1));
            // Here the 3s transaction timeout did stop the statement: a whole-batch database error, which the HTTP entry maps to 503 unknown.
            assertTrue(failed instanceof org.springframework.dao.DataAccessException||failed instanceof org.springframework.transaction.TransactionException,String.valueOf(failed));
            blocker.get(10,TimeUnit.SECONDS);
            assertEquals(0,count("request_id='slow'"));
            assertNotNull(lockTimer("register_batch","wait","not_acquired"),"a lock that was never granted is recorded as not_acquired, with no hold");
        }finally{reg.stop();pool.shutdownNow();}
    }
    @Test void twoNodesRegisteringTheSameRequestOrADoubleClickNeverCreateTwoAttempts()throws Exception{
        ReservationAttemptStore storeA=store(),storeB=store();AttemptRegistrar a=registrar(storeA,2000,4000),b=registrar(storeB,2000,4000);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            for(int round=0;round<15;round++){
                int user=100+round;CyclicBarrier go=new CyclicBarrier(4);
                // same requestId on both nodes (client retry hits another instance) + a new requestId double click
                List<Future<Object>> results=new ArrayList<>();
                for(Object[] call:new Object[][]{{a,"same-"+round},{b,"same-"+round},{a,"click-a-"+round},{b,"click-b-"+round}}){
                    AttemptRegistrar r=(AttemptRegistrar)call[0];String id=(String)call[1];
                    results.add(pool.submit(()->{go.await(5,TimeUnit.SECONDS);
                        try{return r.register(item(user,id,id.startsWith("same")?"fp-same":"fp-"+id).request,"ip",id.startsWith("same")?"fp-same":"fp-"+id,1).get("id");}
                        catch(org.springframework.dao.DuplicateKeyException|BusinessException definite){return definite;}}));
                }
                Set<Object> jobIds=new HashSet<>();
                for(Future<Object> f:results){Object v=f.get(10,TimeUnit.SECONDS);if(!(v instanceof Throwable))jobIds.add(v);}
                assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE user_id=?",Integer.class,(long)user),"one attempt per user/session in round "+round);
                assertEquals(1,jobIds.size(),"every successful receipt names the same attempt: "+jobIds);
                // keep rounds independent of the waiting budget
                db.jdbc.update("UPDATE tb_reservation_job SET state='QUEUE_TIMEOUT',active_owner=NULL WHERE state='WAITING'");
            }
        }finally{a.stop();b.stop();pool.shutdownNow();}
    }
}
