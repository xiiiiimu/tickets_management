package com.ticketing.integration;

import com.ticketing.infrastructure.Db;
import com.ticketing.ticket.*;
import com.ticketing.sale.SaleManifest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.sql.Timestamp;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Selected methods only: owned MySQL + Redis, no load generator, payment, or external AI. */
@TestPropertySource(properties="ticketing.reservations-v2.attempts.ordered-lua=true")
class OrderedQualificationIT extends DurableAttemptLifecycleIT {
    @org.springframework.beans.factory.annotation.Autowired com.ticketing.risk.RiskDecisionService risk;
    @org.springframework.beans.factory.annotation.Autowired com.ticketing.repository.ReservationIntentRepository intentRepository;
    Map<String,Object> qualify(ReserveRequest request){
        reliable(request);Map<String,Object> job=attempts.claim();assertEquals(request.getRequestId(),job.get("request_id"));
        SaleManifest plan=armory.runtimePlan(request.getEventSessionId(),request.getTicketSkuId());
        Map<String,Object> decision=risk.classifyReservation(request.getUserId(),request.getDeviceId(),job.get("ip").toString(),plan.rules(),request.getRequestId());
        assertTrue(Arrays.asList("PASS","REVIEW").contains(decision.get("decision")));
        decision.put("saleVersion",plan.version());
        @SuppressWarnings("unchecked") Map<String,Object> policy=new LinkedHashMap<>((Map<String,Object>)decision.get("policy"));
        policy.put("saleAssemblyVersion",plan.version());policy.put("saleSessionId",plan.sessionId());decision.put("policy",policy);
        job=attempts.decided(job,decision);assertNotNull(job);transactions.prepare(job);return queue.get(job.get("id").toString());
    }
    long head(Sale s){return db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE sku_id=? AND sale_version=1",Long.class,s.sku);}
    @Test void orderedLuaProceedsWhilePredecessorMysqlConfirmationIsPaused()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s)),b=qualify(request(s));
        assertThrows(ReservationQualificationOrder.Pending.class,()->transactions.reserve(b));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        CountDownLatch inConfirmation=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
        doAnswer(call->{call.callRealMethod();ReservationIntent intent=call.getArgument(0);if(intent.getRequestId().equals(a.get("request_id"))){inConfirmation.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));}return null;}).when(inventory).loadRuntimeHold(any(),anyLong());
        try{Future<?> first=executor.submit(()->transactions.reserve(a));assertTrue(inConfirmation.await(4,TimeUnit.SECONDS));
            Future<?> second=executor.submit(()->transactions.reserve(b));second.get(4,TimeUnit.SECONDS);
            assertEquals("PROCESSING",queue.get(a.get("id").toString()).get("state"));assertEquals("RESERVED",queue.get(b.get("id").toString()).get("state"));assertEquals(3,head(s));
            release.countDown();first.get(4,TimeUnit.SECONDS);
        }finally{release.countDown();executor.shutdownNow();}
        assertEquals(98,stock(s));assertEquals(98,redisStock(s));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=? AND status='WAITING_PAYMENT'",Integer.class,s.sku));
    }
    @Test void ambiguousLuaRollbackNeedsTombstoneBeforeAdvancingAndLateCommandCannotResurrect()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s)),b=qualify(request(s));AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();if(fail.getAndSet(false))throw new IllegalStateException("lost reply after actual Redis hold");return result;}).when(inventory).reserveRuntime(any(),anyLong(),anyLong());
        assertThrows(IllegalStateException.class,()->transactions.reserve(a));assertEquals(1,head(s));assertEquals(99,redisStock(s));assertEquals(100,stock(s));
        assertThrows(ReservationQualificationOrder.Pending.class,()->transactions.reserve(b));transactions.abort(a,"EXECUTION_UNAVAILABLE");assertEquals(2,head(s));assertEquals(100,redisStock(s));
        ReservationIntent prior=intentRepository.findByRequestId(a.get("request_id").toString());
        assertEquals(-5,inventory.reserveRuntime(prior,1,System.currentTimeMillis()+600000));assertEquals(100,redisStock(s));
        transactions.reserve(b);assertEquals(99,redisStock(s));assertEquals(99,stock(s));assertEquals("FAILED",queue.get(a.get("id").toString()).get("state"));
    }
    @Test void reclaimedAttemptAfterTurnCommitReusesHoldAndPreservesPaymentDeadline()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s));AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(fail.getAndSet(false))throw new IllegalStateException("interruption after durable Lua turn");return call.callRealMethod();}).when(inventory).loadRuntimeHold(any(),anyLong());
        assertThrows(IllegalStateException.class,()->transactions.reserve(a));assertEquals(2,head(s));assertEquals(99,redisStock(s));assertEquals(100,stock(s));
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1),a.get("id"));Map<String,Object> recovered=attempts.claim();assertEquals(a.get("id"),recovered.get("id"));assertNotEquals(a.get("lease_token"),recovered.get("lease_token"));
        transactions.reserve(a);assertEquals(100,stock(s));transactions.prepare(recovered);transactions.reserve(recovered);
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertEquals(2,head(s));verify(inventory,times(1)).reserveRuntime(any(),eq(1L),anyLong());
        Map<String,Object> completed=queue.get(a.get("id").toString());assertEquals("RESERVED",completed.get("state"));
        Map<Object,Object> marker=redis.opsForHash().entries(com.ticketing.inventory.InventoryService.prefix(s.sku)+"reservation:"+a.get("reservation_id"));
        assertEquals(600000,Long.parseLong(marker.get("expiresAt").toString())-Long.parseLong(marker.get("heldAt").toString()));
    }
}
