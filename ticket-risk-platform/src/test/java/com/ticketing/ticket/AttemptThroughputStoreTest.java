package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Batch registration / batch claim: same per-request rules as the single path, one lock and one commit per batch. */
class AttemptThroughputStoreTest {
    Db db;ReservationDispatch queue;ReservationAttemptStore store;FailingManager manager;DriverManagerDataSource source;
    AtomicLong now=new AtomicLong(1800000000000L);SimpleMeterRegistry metrics;
    static class FailingManager extends DataSourceTransactionManager {
        boolean fail;int commits;
        FailingManager(javax.sql.DataSource ds){super(ds);setRollbackOnCommitFailure(true);}
        @Override protected void doCommit(DefaultTransactionStatus status){if(fail)throw new TransactionSystemException("injected commit failure");commits++;super.doCommit(status);}
    }
    @BeforeEach void setup(){
        source=new DriverManagerDataSource("jdbc:h2:mem:batch-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        db=new Db(new JdbcTemplate(source),new ObjectMapper());manager=new FailingManager(source);metrics=new SimpleMeterRegistry();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(source);
        db.jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY,status VARCHAR(24))");
        db.jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR(36) PRIMARY KEY,status VARCHAR(24))");
        queue=new ReservationDispatch(db,mock(RiskSubjectFence.class),manager);
        ReflectionTestUtils.setField(queue,"capacity",512);ReflectionTestUtils.setField(queue,"workers",4);ReflectionTestUtils.setField(queue,"passWeight",2);ReflectionTestUtils.setField(queue,"reviewAge",2000L);ReflectionTestUtils.setField(queue,"queueTimeout",30000L);queue.initialize();
        db.jdbc.update("UPDATE tb_reservation_dispatch SET runtime_protocol=3 WHERE id=1");
        store=new ReservationAttemptStore(db,queue,manager,metrics,new MockEnvironment());store.clock(()->new Timestamp(now.get()));store.initialize();
    }
    ReserveRequest request(int user,String id){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId((long)user);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setDeviceId("device-"+user);r.setQuantity(1);return r;}
    ReservationAttemptStore.Registration item(int user,String id,String fp){return new ReservationAttemptStore.Registration(request(user,id),"ip-"+user,fp,1);}
    ReservationAttemptStore.Registration item(int user){return item(user,"req-"+user,"fp-"+user);}
    int rows(){return db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class);}
    long timerCount(String operation){io.micrometer.core.instrument.Timer t=metrics.find("attempt.transaction.duration").tags("operation",operation,"outcome","success","phase","total").timer();return t==null?0:t.count();}

    @Test void batchCommitsAllItemsOnceWithSameShapeAsSingleRegister(){
        List<ReservationAttemptStore.Registration> items=new ArrayList<>();for(int i=1;i<=6;i++)items.add(item(i));
        int commitsBefore=manager.commits;
        store.registerBatch(items);
        assertEquals(1,manager.commits-commitsBefore,"one commit per batch");
        assertEquals(1,timerCount("register_batch"));assertEquals(0,timerCount("register"));
        for(ReservationAttemptStore.Registration r:items){
            assertNull(r.failure());assertEquals("WAITING",r.result().get("state"));assertEquals(true,r.result().get("newlyCreated"));
            assertEquals(5000,((Timestamp)r.result().get("waiting_deadline")).getTime()-((Timestamp)r.result().get("created_at")).getTime());
        }
        assertEquals(6,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='WAITING'",Integer.class));
        Map<String,Object> single=store.register(request(99,"single"),"ip","fp-99",1);
        assertEquals(new TreeSet<>(single.keySet()),new TreeSet<>(items.get(0).result().keySet()));
    }
    @Test void laterItemsSeeEarlierInsertsOfTheSameBatch(){
        // same requestId twice, and a second click of the same user/session: identical outcome to sequential register calls
        List<ReservationAttemptStore.Registration> items=Arrays.asList(item(1,"dup","fp-1"),item(1,"dup","fp-1"),item(1,"second-click","fp-1"));
        store.registerBatch(items);
        String id=items.get(0).result().get("id").toString();
        assertEquals(id,items.get(1).result().get("id"));assertEquals(id,items.get(2).result().get("id"));
        assertNull(items.get(1).result().get("newlyCreated"));
        assertEquals(1,rows());
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_request_alias WHERE request_id='second-click'",Integer.class));
    }
    @Test void capacityIsCountedIncludingEarlierItemsAndRefusalsRollBackOnlyTheirItem(){
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=3 WHERE id=1");
        List<ReservationAttemptStore.Registration> items=new ArrayList<>();for(int i=1;i<=5;i++)items.add(item(i));
        store.registerBatch(items);
        for(int i=0;i<3;i++)assertNull(items.get(i).failure());
        for(int i=3;i<5;i++){assertTrue(items.get(i).failure() instanceof AttemptAdmissionException);assertEquals(429,((AttemptAdmissionException)items.get(i).failure()).status);}
        assertEquals(3,rows());
        assertEquals(2.0,metrics.get("attempt.admission.rejected.internal").tags("reason","CAPACITY_REJECT","detail","hard_capacity").counter().count());
    }
    @Test void businessRefusalInTheMiddleDoesNotLoseNeighbours(){
        store.register(request(1,"existing"),"ip","fp-original",1);
        List<ReservationAttemptStore.Registration> items=Arrays.asList(item(2),item(1,"existing","fp-different"),item(3));
        store.registerBatch(items);
        assertNull(items.get(0).failure());assertNull(items.get(2).failure());
        assertTrue(items.get(1).failure() instanceof BusinessException);assertEquals(409,((BusinessException)items.get(1).failure()).status);
        assertEquals(3,rows());
    }
    @Test void wholeBatchCommitFailureLeavesNoRowsAndPropagates(){
        manager.fail=true;
        List<ReservationAttemptStore.Registration> items=Arrays.asList(item(1),item(2));
        assertThrows(TransactionSystemException.class,()->store.registerBatch(items));
        manager.fail=false;assertEquals(0,rows());
    }
    @Test void batchClaimHonoursPersistedActiveLimitAcrossItsOwnGrants(){
        for(int i=1;i<=6;i++)store.register(request(i,"r"+i),"ip","fp-"+i,1);
        List<Map<String,Object>> first=store.claimBatch(3);
        assertEquals(3,first.size());for(Map<String,Object> j:first){assertEquals("PROCESSING",j.get("state"));assertNotNull(j.get("lease_token"));}
        List<Map<String,Object>> second=store.claimBatch(5);
        assertEquals(1,second.size(),"active_limit=4 is global and counts grants made earlier in the same batch");
        assertTrue(store.claimBatch(5).isEmpty());
        Set<Object> ids=new HashSet<>();for(Map<String,Object> j:first)ids.add(j.get("id"));for(Map<String,Object> j:second)ids.add(j.get("id"));assertEquals(4,ids.size());
        assertEquals(3,timerCount("claim_batch"));
        assertEquals(4.0,metrics.get("attempt.claim.granted").tag("kind","first").counter().count());
    }
    @Test void batchClaimExpiresInsteadOfLateGrantAndPrefersStaleRecovery(){
        store.register(request(1,"old"),"ip","fp-1",1);
        Map<String,Object> processing=store.claim();
        now.addAndGet(ReservationAttemptStore.LEASE_MILLIS+1); // lease of 'old' is stale now, and anything registered before is past 5s
        store.register(request(2,"fresh"),"ip","fp-2",1);
        db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,reservation_id,order_id,attempt_protocol,waiting_deadline,created_at) VALUES('late','late','fp',3,'session','sku',1,'d','ip',1,'WAITING',?,'late-r','late-o',3,?,?)",new Timestamp(now.get()-1),new Timestamp(now.get()-1),new Timestamp(now.get()-5001));
        List<Map<String,Object>> granted=store.claimBatch(3);
        assertEquals(2,granted.size());
        assertEquals(processing.get("id"),granted.get(0).get("id"),"stale lease is recovered first");
        assertNotEquals(processing.get("lease_token"),granted.get(0).get("lease_token"));
        assertEquals("fresh",granted.get(1).get("request_id"));
        assertEquals("QUEUE_TIMEOUT",queue.get("late").get("state"));
    }
    @Test void zeroOrNegativeBatchClaimDoesNothing(){store.register(request(1,"x"),"ip","fp",1);assertTrue(store.claimBatch(0).isEmpty());assertEquals("WAITING",queue.byRequest("x").get("state"));}
    @Test void admissionEstimateMatchesRegisterRule(){
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=3 WHERE id=1");
        int[] empty=store.admissionEstimate();assertEquals(0,empty[0]);assertEquals(3,empty[1]);
        store.register(request(1,"a"),"ip","fp-1",1);store.register(request(2,"b"),"ip","fp-2",1);store.claim();
        int[] later=store.admissionEstimate();assertEquals(1,later[0]);assertEquals(2,later[1],"capacity minus active");
    }
}
