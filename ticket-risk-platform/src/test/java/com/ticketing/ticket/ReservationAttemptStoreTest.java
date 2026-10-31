package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationAttemptStoreTest {
    Db db;ReservationDispatch queue;ReservationAttemptStore store;FailingManager manager;DriverManagerDataSource source;
    AtomicLong now=new AtomicLong(1800000000000L);SimpleMeterRegistry metrics;
    static class FailingManager extends DataSourceTransactionManager {
        boolean fail;
        FailingManager(javax.sql.DataSource ds){super(ds);setRollbackOnCommitFailure(true);}
        @Override protected void doCommit(DefaultTransactionStatus status){if(fail)throw new TransactionSystemException("injected commit failure");super.doCommit(status);}
    }
    @BeforeEach void setup(){
        source=new DriverManagerDataSource("jdbc:h2:mem:attempt-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        db=new Db(new JdbcTemplate(source),new ObjectMapper());manager=new FailingManager(source);metrics=new SimpleMeterRegistry();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(source);
        db.jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY,status VARCHAR(24))");
        db.jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR(36) PRIMARY KEY,status VARCHAR(24))");
        queue=new ReservationDispatch(db,mock(RiskSubjectFence.class),manager);
        ReflectionTestUtils.setField(queue,"capacity",512);ReflectionTestUtils.setField(queue,"workers",4);ReflectionTestUtils.setField(queue,"passWeight",2);ReflectionTestUtils.setField(queue,"reviewAge",2000L);ReflectionTestUtils.setField(queue,"queueTimeout",30000L);queue.initialize();
        db.jdbc.update("UPDATE tb_reservation_dispatch SET runtime_protocol=3 WHERE id=1");
        store=instance();
    }
    ReservationAttemptStore instance(){ReservationAttemptStore s=new ReservationAttemptStore(db,queue,manager,metrics,new MockEnvironment());s.clock(()->new Timestamp(now.get()));s.initialize();return s;}
    ReserveRequest request(int i){ReserveRequest r=new ReserveRequest();r.setRequestId("attempt-request-"+i);r.setUserId((long)i);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setDeviceId("device-"+i);r.setQuantity(1);return r;}
    Map<String,Object> register(int i){return store.register(request(i),"ip-"+i,"fp-"+i,1);}
    @Test void waitingIsCommittedBeforeReturnAndNoLeaseOrStockExists()throws Exception{
        Map<String,Object> job=register(1);assertEquals("WAITING",job.get("state"));assertNull(job.get("lease_token"));
        assertEquals(5000,((Timestamp)job.get("waiting_deadline")).getTime()-((Timestamp)job.get("created_at")).getTime());
        JdbcTemplate independent=new JdbcTemplate(source);assertEquals(1,independent.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='WAITING'",Integer.class));
        verifyNoInteractions((RiskSubjectFence)ReflectionTestUtils.getField(queue,"fences"));
    }
    @Test void claimBeforeDeadlineEntersProcessingAndPreservesDeadline(){
        Map<String,Object> job=register(1);now.addAndGet(4999);Map<String,Object> claimed=store.claim();
        assertEquals("PROCESSING",claimed.get("state"));assertNotNull(claimed.get("lease_token"));assertEquals(job.get("waiting_deadline"),claimed.get("waiting_deadline"));
    }
    @Test void exactDeadlineIsQueueTimeoutAndCannotBeClaimedOrRevived(){
        Map<String,Object> job=register(1);now.addAndGet(5000);assertNull(store.claim());
        assertEquals("QUEUE_TIMEOUT",queue.get(job.get("id").toString()).get("state"));assertEquals(true,queue.view(queue.get(job.get("id").toString()),1).get("waitExpired"));now.addAndGet(60000);assertNull(store.claim());
        assertEquals(job.get("id"),register(1).get("id"));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));
        ReserveRequest next=request(1);next.setRequestId("explicit-new-click");assertNotEquals(job.get("id"),store.register(next,"ip-1","fp-1",1).get("id"));
    }
    @Test void freshTimeAfterLockCannotAuthorizeUsingStaleBeforeLockTime(){
        Map<String,Object> job=register(1);long deadline=((Timestamp)job.get("waiting_deadline")).getTime();AtomicInteger reads=new AtomicInteger();
        store.clock(()->new Timestamp(reads.incrementAndGet()==1?deadline-1:deadline));assertNull(store.claim());assertEquals("QUEUE_TIMEOUT",queue.get(job.get("id").toString()).get("state"));
    }
    @Test void claimAndTimeoutRaceHasOneTerminalOwner()throws Exception{
        Map<String,Object> job=register(1);now.addAndGet(5000);ReservationAttemptStore second=instance();CyclicBarrier go=new CyclicBarrier(2);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<Map<String,Object>> claim=workers.submit(()->{go.await(3,TimeUnit.SECONDS);return store.claim();});
            Future<?> timeout=workers.submit(()->{go.await(3,TimeUnit.SECONDS);second.expireWaiting();return null;});
            assertNull(claim.get(5,TimeUnit.SECONDS));timeout.get(5,TimeUnit.SECONDS);
            assertEquals("QUEUE_TIMEOUT",queue.get(job.get("id").toString()).get("state"));
            assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_risk_audit WHERE result_code='QUEUE_TIMEOUT'",Integer.class));
        }finally{workers.shutdownNow();}
    }
    @Test void processingSurvivesThirtySecondsAndRecoveryKeepsIdentity(){
        Map<String,Object> registered=register(1),first=store.claim();now.addAndGet(31000);store.expireWaiting();assertEquals("PROCESSING",store.refresh(first.get("id").toString()).get("state"));
        Map<String,Object> recovered=store.claim();assertEquals(first.get("id"),recovered.get("id"));assertEquals(registered.get("waiting_deadline"),recovered.get("waiting_deadline"));assertEquals(first.get("processing_started_at"),recovered.get("processing_started_at"));assertNotEquals(first.get("lease_token"),recovered.get("lease_token"));
        Map<String,Object> pass=new HashMap<>();pass.put("decision","PASS");pass.put("resultCode","PASS");
        assertNull(store.decided(first,pass));assertNotNull(store.decided(recovered,pass));assertEquals("PROCESSING",queue.get(first.get("id").toString()).get("state"));
    }
    @Test void heartbeatPreservesLeaseAndOldTokenCannotRenewOrDecide(){
        register(1);Map<String,Object> first=store.claim();now.addAndGet(14000);assertTrue(store.heartbeat(first));now.addAndGet(2000);assertNull(store.claim());
        now.addAndGet(15000);Map<String,Object> recovered=store.claim();assertFalse(store.heartbeat(first));assertTrue(store.heartbeat(recovered));
    }
    @Test void dispatchHintDoesNotTreatAnActiveLeaseAsAvailableWork(){register(1);assertEquals(1,store.availableHint());store.claim();assertEquals(0,store.availableHint());now.addAndGet(15001);assertEquals(1,store.availableHint());}
    @Test void sharedSummarySchedulerCannotBeUsedForDurableExpiry(){ReservationDispatchScheduler shared=mock(ReservationDispatchScheduler.class);when(shared.isIsolated()).thenReturn(false);ReflectionTestUtils.setField(store,"scheduler",shared);assertThrows(IllegalStateException.class,store::initialize);}
    @Test void idempotentAliasesReturnSameValidAttemptAndValidateFingerprint(){
        Map<String,Object> first=register(1);assertEquals(first.get("id"),register(1).get("id"));ReserveRequest retry=request(1);retry.setRequestId("different-http-id");
        assertEquals(first.get("id"),store.register(retry,"ip-1","fp-1",1).get("id"));assertEquals(first.get("id"),queue.byRequest("different-http-id").get("id"));
        assertThrows(com.ticketing.common.BusinessException.class,()->store.register(retry,"ip","different",1));
    }
    @Test void admissionPredictionIsBoundedAndRetriesBypassFullQueue(){
        for(int i=1;i<=12;i++)register(i);
        assertEquals("SYSTEM_BUSY",assertThrows(AttemptAdmissionException.class,()->register(13)).code);
        assertEquals(1,metrics.get("attempt.admission.rejected.internal").tags("reason","CAPACITY_REJECT","detail","waiting_budget").counter().count());
        assertEquals("WAITING",register(1).get("state"));assertEquals(12,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));
    }
    @Test void perUserBoundRemainsEnforcedAcrossDifferentSessions(){for(int i=1;i<=4;i++){ReserveRequest r=request(i);r.setUserId(1L);r.setEventSessionId("s-"+i);store.register(r,"ip","fp-"+i,1);}ReserveRequest extra=request(5);extra.setUserId(1L);extra.setEventSessionId("s-5");assertThrows(AttemptAdmissionException.class,()->store.register(extra,"ip","fp-5",1));ReserveRequest retry=request(1);retry.setEventSessionId("s-1");assertEquals("WAITING",store.register(retry,"ip","fp-1",1).get("state"));}
    @Test void twoInstancesNeverDuplicateClaimOrExceedGlobalActiveLimit()throws Exception{
        for(int i=1;i<=8;i++)register(i);ReservationAttemptStore second=instance();ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Map<String,Object>>> fs=new ArrayList<>();for(int i=0;i<8;i++){ReservationAttemptStore target=i%2==0?store:second;fs.add(pool.submit(()->{assertTrue(start.await(3,TimeUnit.SECONDS));return target.claim();}));}start.countDown();Set<Object> ids=new HashSet<>();for(Future<Map<String,Object>> f:fs){Map<String,Object> job=f.get(8,TimeUnit.SECONDS);if(job!=null)assertTrue(ids.add(job.get("id")));}assertEquals(4,ids.size());}
        finally{pool.shutdownNow();}
    }
    @Test void failedRegistrationCommitRollsBackAndDoesNotCreateAReceipt(){
        manager.fail=true;assertThrows(TransactionSystemException.class,()->register(1));manager.fail=false;
        assertEquals(1,metrics.get("attempt.transaction.duration").tags("operation","register","phase","total","outcome","failure").timer().count());
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));assertEquals("WAITING",register(1).get("state"));
    }
    @Test void failedClaimCommitLeavesWaitingAndLeaseUnset(){
        Map<String,Object> job=register(1);manager.fail=true;assertThrows(TransactionSystemException.class,()->store.claim());manager.fail=false;
        Map<String,Object> current=queue.get(job.get("id").toString());assertEquals("WAITING",current.get("state"));assertNull(current.get("lease_token"));
        assertEquals(1,metrics.get("attempt.transaction.duration").tags("operation","claim","phase","total","outcome","failure").timer().count());assertNull(metrics.find("attempt.claim.granted").counter());
    }
    @Test void startupGuardCannotClaimOrExpireAWaitingAttemptUntilReady(){
        Map<String,Object> job=register(1);AttemptWorkerReadiness guard=mock(AttemptWorkerReadiness.class);ReflectionTestUtils.setField(store,"readiness",guard);
        assertNull(store.claim());assertEquals("WAITING",queue.get(job.get("id").toString()).get("state"));assertNull(metrics.find("attempt.transaction.duration").tags("operation","claim").timer());
        when(guard.isReady()).thenReturn(true);assertEquals(job.get("id"),store.claim().get("id"));assertEquals(1,metrics.get("attempt.transaction.duration").tags("operation","claim","phase","total","outcome","success").timer().count());
    }
    @Test void registrationCommitIsIndependentOfAnOuterRollback(){org.springframework.transaction.support.TransactionTemplate outer=new org.springframework.transaction.support.TransactionTemplate(manager);outer.executeWithoutResult(s->{register(1);s.setRollbackOnly();});assertEquals("WAITING",queue.byRequest("attempt-request-1").get("state"));}
    @Test void legacyOrphanIntentBlocksActivationWithoutChangingIt(){db.jdbc.update("INSERT INTO tb_reserve_request(request_id,status) VALUES('legacy-orphan','REQUESTED')");assertThrows(IllegalStateException.class,store::initialize);assertEquals("REQUESTED",db.one("SELECT status FROM tb_reserve_request WHERE request_id='legacy-orphan'").get("status"));}
    @Test void blockDecisionIsFinalAndStaleWorkerCannotOverwrite(){
        register(1);Map<String,Object> job=store.claim(),block=new HashMap<>();block.put("decision","BLOCK");block.put("resultCode","PROFILE_BLOCK");assertNull(store.decided(job,block));
        assertEquals("REJECTED",queue.get(job.get("id").toString()).get("state"));assertFalse(store.heartbeat(job));assertNull(store.claim());
    }
}
