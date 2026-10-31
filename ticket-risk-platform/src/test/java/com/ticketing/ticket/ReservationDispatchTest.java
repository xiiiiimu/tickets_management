package com.ticketing.ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationDispatchTest {
    Db db;ReservationDispatch queue;DataSourceTransactionManager manager;RiskSubjectFence fences;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:dispatch-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());manager=new DataSourceTransactionManager(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql")).execute(ds);
        db.jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR PRIMARY KEY)");
        db.jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,status VARCHAR,expires_at TIMESTAMP)");
        fences=new RiskSubjectFence(db,mock(StringRedisTemplate.class));queue=instance(10,2);
    }
    ReservationDispatch instance(int capacity,int workers){
        ReservationDispatch q=new ReservationDispatch(db,fences,manager);
        ReflectionTestUtils.setField(q,"capacity",capacity);ReflectionTestUtils.setField(q,"workers",workers);
        ReflectionTestUtils.setField(q,"passWeight",2);ReflectionTestUtils.setField(q,"reviewAge",1000000L);ReflectionTestUtils.setField(q,"queueTimeout",30000L);q.initialize();return q;
    }
    ReserveRequest request(int n){ReserveRequest r=new ReserveRequest();r.setRequestId("request-"+n);r.setUserId((long)n);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setQuantity(1);r.setDeviceId("d-"+n);return r;}
    Map<String,Object> enqueue(int n,String decision){Map<String,Object> job=queue.create(request(n),"ip","fp-"+n,1);Map<String,Object> d=new HashMap<>();d.put("decision",decision);d.put("resultCode",decision);queue.decided(job,d);return queue.get(job.get("id").toString());}
    void finish(Map<String,Object> job){db.jdbc.update("UPDATE tb_reservation_job SET state='FAILED',active_owner=NULL,lease_token=NULL WHERE id=?",job.get("id"));}
    @Test void weightedOpportunitiesAndReviewAgingAreBounded(){
        enqueue(1,"REVIEW");enqueue(2,"PASS");enqueue(3,"PASS");enqueue(4,"PASS");
        List<String> selected=new ArrayList<>();for(int i=0;i<4;i++){Map<String,Object> job=queue.claim();selected.add(job.get("decision").toString());finish(job);}
        assertEquals(Arrays.asList("PASS","PASS","REVIEW","PASS"),selected);
        enqueue(5,"REVIEW");enqueue(6,"PASS");enqueue(7,"PASS");
        db.jdbc.update("UPDATE tb_reservation_dispatch SET review_age_ms=1,pass_run=0");
        Map<String,Object> first=queue.claim();assertEquals("PASS",first.get("decision"));finish(first);
        Map<String,Object> second=queue.claim();assertEquals("REVIEW",second.get("decision"));
    }
    @Test void blockIsAuditedAndNeverBecomesInventoryWork(){
        Map<String,Object> job=enqueue(1,"BLOCK");assertEquals("REJECTED",job.get("state"));assertNull(queue.claim());
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_risk_audit WHERE decision='BLOCK'",Integer.class));
        assertNull(job.get("active_owner"));
        assertNull(job.get("queued_at"));
    }
    @Test void queueClockExcludesEarlierRiskEvaluationAndUsesDatabaseClaimTime(){
        Map<String,Object> job=queue.create(request(1),"ip","fp",1);
        db.jdbc.update("UPDATE tb_reservation_job SET created_at=? WHERE id=?",new Timestamp(System.currentTimeMillis()-10000),job.get("id"));
        Map<String,Object> decision=new HashMap<>();decision.put("decision","PASS");decision.put("resultCode","PASS");queue.decided(job,decision);
        Map<String,Object> claimed=queue.claim();assertNotNull(claimed.get("queued_at"));assertNotNull(claimed.get("claimed_at"));
        long created=((Timestamp)claimed.get("created_at")).getTime(),queued=((Timestamp)claimed.get("queued_at")).getTime(),at=((Timestamp)claimed.get("claimed_at")).getTime();
        assertTrue(queued-created>=9000);assertTrue(at>=queued);
    }
    @Test void duplicateIdsAndSameOwnerAliasesRemainIdempotentAfterTerminalState(){
        ReserveRequest r=request(1);Map<String,Object> first=queue.create(r,"ip","fp",1);
        assertEquals(first.get("id"),queue.create(r,"ip","fp",1).get("id"));
        r.setRequestId("another-click");Map<String,Object> alias=queue.create(r,"ip","fp",1);assertEquals(first.get("id"),alias.get("id"));
        finish(first);assertEquals(first.get("id"),queue.byRequest("another-click").get("id"));
        assertThrows(BusinessException.class,()->queue.create(r,"ip","different-fp",1));
    }
    @Test void boundedAdmissionAndTwoInstancesNeverClaimSameWorkOrExceedGlobalLimit()throws Exception{
        for(int n=1;n<=10;n++)enqueue(n,"PASS");
        assertThrows(BusinessException.class,()->queue.create(request(11),"ip","fp-11",1));
        ReservationDispatch other=instance(10,2);ExecutorService pool=Executors.newFixedThreadPool(8);
        try{
            List<Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<16;i++){ReservationDispatch q=i%2==0?queue:other;results.add(pool.submit(q::claim));}
            Set<Object> ids=new HashSet<>();int claimed=0;
            for(Future<Map<String,Object>> f:results){Map<String,Object> value=f.get(10,TimeUnit.SECONDS);if(value!=null){claimed++;assertTrue(ids.add(value.get("id")));}}
            assertEquals(2,claimed);
        }finally{pool.shutdownNow();}
    }
    @Test void crashedWorkerGetsNewLeaseWithoutNewReservationOrDeadline(){
        Map<String,Object> original=enqueue(1,"PASS"),claimed=queue.claim();
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-60000),claimed.get("id"));
        Map<String,Object> recovered=queue.claim();assertNotEquals(claimed.get("lease_token"),recovered.get("lease_token"));
        assertEquals("CLAIMED",recovered.get("state"));assertEquals(original.get("reservation_id"),recovered.get("reservation_id"));
        assertEquals(original.get("queue_expires_at"),recovered.get("queue_expires_at"));assertFalse(queue.owns(recovered,claimed,"CLAIMED"));
    }
    @Test void staleRiskEvaluatorCannotOverwriteRecoveryDecision(){
        Map<String,Object> first=queue.create(request(1),"ip","fp",1);
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-60000),first.get("id"));
        Map<String,Object> recovered=queue.claim();Map<String,Object> block=new HashMap<>();block.put("decision","BLOCK");block.put("resultCode","PROFILE_BLOCK");
        queue.decided(recovered,block);Map<String,Object> pass=new HashMap<>();pass.put("decision","PASS");pass.put("resultCode","PASS");queue.decided(first,pass);
        assertEquals("REJECTED",queue.get(first.get("id").toString()).get("state"));
    }
}
