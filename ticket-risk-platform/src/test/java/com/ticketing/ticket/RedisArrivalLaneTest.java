package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Redis-arrival SKU lane: intent before Lua and durable lua_turn_finished stay; the MySQL cursor and its row lock go. */
class RedisArrivalLaneTest {
    ReservationAttemptTransactionTest f;ReservationQualificationOrder order;
    @BeforeEach void setup(){
        f=new ReservationAttemptTransactionTest();f.setup();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql")).execute(f.db.jdbc.getDataSource());
        order=new ReservationQualificationOrder(f.db,true);
        ReflectionTestUtils.setField(f.service,"qualificationOrder",order);
        f.seed(31000,3);
        doAnswer(a->{ReservationIntent r=a.getArgument(0);r.setInventoryBucket(0);r.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return null;}).when(f.inventory).loadRuntimeHold(any(),anyLong());
    }
    Map<String,Object> fresh(String id){return f.queue.get(id);}
    int laneRows(){return f.db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_sku_order",Integer.class);}
    Map<String,Object> second(){
        f.db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,admission,lease_token,lease_until,queue_expires_at,reservation_id,order_id,attempt_protocol,waiting_deadline,processing_started_at) SELECT 'job2','r2','fp2',2,session_id,sku_id,quantity,'device2','ip2',sale_version,state,decision,admission,'token2',lease_until,queue_expires_at,'res2','order2',attempt_protocol,waiting_deadline,processing_started_at FROM tb_reservation_job WHERE id='job'");
        ReservationIntent r=new ReservationIntent();r.setRequestId("r2");r.setFingerprint("fp2");r.setReservationId("res2");r.setOrderId("order2");r.setUserId(2);r.setSkuId("sku");r.setQuantity(1);r.setDeviceId("device2");r.setIp("ip2");r.setStatus("REQUESTED");
        when(f.intents.findByRequestId("r2")).thenReturn(r);when(f.intents.findByRequestIdForUpdate("r2")).thenReturn(r);
        when(f.skus.tryDecreaseStockForReservation(r)).thenReturn(true);when(f.purchases.tryIncreaseWithinLimit(2L,"sku",1,2)).thenReturn(true);
        when(f.inventory.reserveRuntime(eq(r),eq(1L),anyLong())).thenAnswer(a->{r.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return 100L;});
        return fresh("job2");
    }

    @Test void prepareCommitsIntentWithoutAnySqlCursorOrSequence(){
        when(f.intents.findByRequestId("r")).thenReturn(null); // first preparation inserts the reliable intent
        f.service.prepare(f.job);
        verify(f.intents).insertRequested(eq("r"),eq("fp"),eq("res"),eq("order"),any(),any(),eq(1),eq("device"),eq("ip"),eq("job"));
        assertNull(fresh("job").get("qualification_seq"));assertEquals(0,laneRows());
    }
    @Test void reserveFinishesTurnDurablyThenConfirmsFromTheHoldMarker(){
        f.service.prepare(f.job);f.service.reserve(f.job);
        Map<String,Object> done=fresh("job");
        assertEquals("RESERVED",done.get("state"));assertEquals(1,((Number)done.get("lua_turn_finished")).intValue());
        org.mockito.InOrder inOrder=inOrder(f.inventory);
        inOrder.verify(f.inventory).reserveRuntime(f.intent,1,f.sku.getSaleEndTime().getTime());
        inOrder.verify(f.inventory).loadRuntimeHold(f.intent,1);
        assertEquals(0,laneRows());
    }
    @Test void laterQualifiedRequestIsNeverPendingBehindAnEarlierOne(){
        Map<String,Object> job2=second();
        f.service.prepare(f.job);f.service.prepare(job2); // job qualified first
        f.service.reserve(job2);                           // but job2 reaches Redis first: allowed, no Pending
        assertEquals("RESERVED",fresh("job2").get("state"));assertEquals("PROCESSING",fresh("job").get("state"));
        f.service.reserve(f.job);assertEquals("RESERVED",fresh("job").get("state"));
    }
    @Test void retryAfterLuaButBeforeTurnCommitReusesTheIdempotentHold(){
        f.service.prepare(f.job);
        // Lua succeeded but the worker died before lua_turn_finished committed: the retry calls the idempotent Lua again.
        when(f.inventory.reserveRuntime(eq(f.intent),eq(1L),anyLong())).thenAnswer(a->{f.intent.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return 105L;});
        f.service.reserve(f.job);
        assertEquals("RESERVED",fresh("job").get("state"));verify(f.inventory,times(1)).reserveRuntime(any(),anyLong(),anyLong());
    }
    @Test void finishedTurnIsNotRepeated(){
        f.service.prepare(f.job);
        when(f.skus.tryDecreaseStockForReservation(f.intent)).thenReturn(false);
        assertThrows(ReservationV2Transactions.Rejected.class,()->f.service.reserve(f.job));
        assertEquals(1,((Number)fresh("job").get("lua_turn_finished")).intValue());
        when(f.skus.tryDecreaseStockForReservation(f.intent)).thenReturn(true);
        f.service.reserve(f.job);
        verify(f.inventory,times(1)).reserveRuntime(any(),anyLong(),anyLong());assertEquals("RESERVED",fresh("job").get("state"));
    }
    @Test void abortWritesTombstoneWithoutCursorWork(){
        f.service.prepare(f.job);f.service.abort(f.job,"EXECUTION_UNAVAILABLE");
        verify(f.inventory).abortRuntime(f.intent,1);assertEquals("FAILED",fresh("job").get("state"));assertEquals(0,laneRows());
    }
    @Test void rejectedLuaDoesNotFinishTheTurn(){
        f.service.prepare(f.job);when(f.inventory.reserveRuntime(eq(f.intent),eq(1L),anyLong())).thenReturn(-3L);
        assertEquals("SOLD_OUT",assertThrows(ReservationV2Transactions.Rejected.class,()->f.service.reserve(f.job)).code);
        assertEquals(0,((Number)fresh("job").get("lua_turn_finished")).intValue());
    }
    @Test void publishedSqlCursorTurnIsRefusedInsteadOfSilentlySkipped(){
        f.db.jdbc.update("UPDATE tb_reservation_job SET qualification_seq=7 WHERE id='job'");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(f.db.jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->order.aborted(fresh("job"))));
    }

    // ---- persisted mode activation on ReservationAttemptStore.initialize ----
    static Db storeDb(boolean v114){
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:lane-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        List<ClassPathResource> scripts=new ArrayList<>(Arrays.asList(new ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql"),new ClassPathResource("db/migration/V111__qualification_lua_order.sql")));
        if(v114)scripts.add(new ClassPathResource("db/migration/V114__attempt_redis_arrival_lane.sql"));
        new ResourceDatabasePopulator(scripts.toArray(new ClassPathResource[0])).execute(source);
        Db db=new Db(new JdbcTemplate(source),new ObjectMapper());
        db.jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY,status VARCHAR(24))");
        db.jdbc.update("INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol,ordered_lua_enabled) VALUES(1,2,512,4,2000,3,1)");
        return db;
    }
    static ReservationAttemptStore store(Db db,boolean lane){
        MockEnvironment env=new MockEnvironment().withProperty("ticketing.reservations-v2.attempts.ordered-lua","true").withProperty("ticketing.reservations-v2.attempts.redis-ordered-lane",Boolean.toString(lane));
        return new ReservationAttemptStore(db,mock(ReservationDispatch.class),new DataSourceTransactionManager(db.jdbc.getDataSource()),new SimpleMeterRegistry(),env);
    }
    @Test void activationRequiresV114AndTheSamePersistedModeOnEveryNode(){
        assertTrue(assertThrows(IllegalStateException.class,()->store(storeDb(false),true).initialize()).getMessage().contains("V114"));
        store(storeDb(false),false).initialize(); // old schema, lane off: unchanged behaviour
        Db db=storeDb(true);
        assertThrows(IllegalStateException.class,()->store(db,true).initialize(),"flag on but persisted 0");
        db.jdbc.update("UPDATE tb_reservation_dispatch SET redis_lane_enabled=1");
        store(db,true).initialize();
        assertThrows(IllegalStateException.class,()->store(db,false).initialize(),"persisted 1 but node flag off");
    }
    @Test void activationRequiresDrainedSqlCursorTurnsAndDeactivationRequiresDrainedArrivalWork(){
        Db db=storeDb(true);db.jdbc.update("UPDATE tb_reservation_dispatch SET redis_lane_enabled=1");
        db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,admission,queue_expires_at,reservation_id,order_id,attempt_protocol,qualification_seq) VALUES('a','a','fp',1,'s','sku',1,'d','ip',1,'PROCESSING','PASS','{}',CURRENT_TIMESTAMP(3),'ra','oa',3,1)");
        assertTrue(assertThrows(IllegalStateException.class,()->store(db,true).initialize()).getMessage().contains("Drain SKU-cursor"));
        db.jdbc.update("UPDATE tb_reservation_job SET qualification_seq=NULL");
        store(db,true).initialize();
        db.jdbc.update("UPDATE tb_reservation_dispatch SET redis_lane_enabled=0");
        assertTrue(assertThrows(IllegalStateException.class,()->store(db,false).initialize()).getMessage().contains("Drain unordered"));
    }
    @Test void laneRequiresOrderedLua(){
        Db db=storeDb(true);MockEnvironment env=new MockEnvironment().withProperty("ticketing.reservations-v2.attempts.redis-ordered-lane","true");
        assertThrows(IllegalStateException.class,()->new ReservationAttemptStore(db,mock(ReservationDispatch.class),new DataSourceTransactionManager(db.jdbc.getDataSource()),new SimpleMeterRegistry(),env));
    }
}
