package com.ticketing.integration;

import com.ticketing.ticket.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Redis-arrival counterparts of OrderedQualificationIT on real MySQL + Redis (selected methods only).
 * Requires the owned schema's tb_reservation_dispatch.redis_lane_enabled=1 (V114).
 * The SQL-cursor assertions (Pending, next_lua_seq) are intentionally replaced: arrival order at Redis is the order.
 */
@TestPropertySource(properties={"ticketing.reservations-v2.attempts.ordered-lua=true","ticketing.reservations-v2.attempts.redis-ordered-lane=true"})
class RedisArrivalLaneIT extends DurableAttemptLifecycleIT {
    @org.springframework.beans.factory.annotation.Autowired com.ticketing.risk.RiskDecisionService risk;
    @org.springframework.beans.factory.annotation.Autowired com.ticketing.repository.ReservationIntentRepository intentRepository;
    /** Same qualification steps as OrderedQualificationIT.qualify. */
    Map<String,Object> qualify(ReserveRequest request){
        reliable(request);Map<String,Object> job=attempts.claim();assertEquals(request.getRequestId(),job.get("request_id"));
        com.ticketing.sale.SaleManifest plan=armory.runtimePlan(request.getEventSessionId(),request.getTicketSkuId());
        Map<String,Object> decision=risk.classifyReservation(request.getUserId(),request.getDeviceId(),job.get("ip").toString(),plan.rules(),request.getRequestId());
        assertTrue(Arrays.asList("PASS","REVIEW").contains(decision.get("decision")));
        decision.put("saleVersion",plan.version());
        @SuppressWarnings("unchecked") Map<String,Object> policy=new LinkedHashMap<>((Map<String,Object>)decision.get("policy"));
        policy.put("saleAssemblyVersion",plan.version());policy.put("saleSessionId",plan.sessionId());decision.put("policy",policy);
        job=attempts.decided(job,decision);assertNotNull(job);transactions.prepare(job);return queue.get(job.get("id").toString());
    }
    int laneRows(Sale s){return db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_sku_order WHERE sku_id=?",Integer.class,s.sku);}
    @Test void laterQualifiedReservesWhilePredecessorConfirmationIsPausedAndNoCursorExists()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s)),b=qualify(request(s));
        assertNull(a.get("qualification_seq"));assertNull(b.get("qualification_seq"));assertEquals(0,laneRows(s));
        CountDownLatch inConfirmation=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
        doAnswer(call->{call.callRealMethod();ReservationIntent intent=call.getArgument(0);if(intent.getRequestId().equals(a.get("request_id"))){inConfirmation.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));}return null;}).when(inventory).loadRuntimeHold(any(),anyLong());
        try{
            Future<?> first=executor.submit(()->transactions.reserve(a));assertTrue(inConfirmation.await(4,TimeUnit.SECONDS));
            Future<?> second=executor.submit(()->transactions.reserve(b));second.get(4,TimeUnit.SECONDS);
            assertEquals("PROCESSING",queue.get(a.get("id").toString()).get("state"));assertEquals("RESERVED",queue.get(b.get("id").toString()).get("state"));
            release.countDown();first.get(4,TimeUnit.SECONDS);
        }finally{release.countDown();executor.shutdownNow();}
        assertEquals(98,stock(s));assertEquals(98,redisStock(s));assertEquals(0,laneRows(s));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=? AND status='WAITING_PAYMENT'",Integer.class,s.sku));
    }
    @Test void secondQualifiedCanReachRedisFirst()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s)),b=qualify(request(s));
        transactions.reserve(b);assertEquals("RESERVED",queue.get(b.get("id").toString()).get("state"));assertEquals("PROCESSING",queue.get(a.get("id").toString()).get("state"));
        transactions.reserve(a);assertEquals("RESERVED",queue.get(a.get("id").toString()).get("state"));
        assertEquals(98,stock(s));assertEquals(98,redisStock(s));
    }
    @Test void ambiguousLuaIsTombstonedByAbortAndLateCommandCannotResurrect()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s)),b=qualify(request(s));AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod();if(fail.getAndSet(false))throw new IllegalStateException("lost reply after actual Redis hold");return result;}).when(inventory).reserveRuntime(any(),anyLong(),anyLong());
        assertThrows(IllegalStateException.class,()->transactions.reserve(a));assertEquals(99,redisStock(s));assertEquals(100,stock(s));
        assertEquals(0,((Number)queue.get(a.get("id").toString()).get("lua_turn_finished")).intValue());
        transactions.reserve(b);assertEquals(98,redisStock(s));assertEquals(99,stock(s)); // b is not blocked by a's unknown outcome
        transactions.abort(a,"EXECUTION_UNAVAILABLE");assertEquals(99,redisStock(s));
        ReservationIntent prior=intentRepository.findByRequestId(a.get("request_id").toString());
        assertEquals(-5,inventory.reserveRuntime(prior,1,System.currentTimeMillis()+600000));assertEquals(99,redisStock(s));
        assertEquals("FAILED",queue.get(a.get("id").toString()).get("state"));assertEquals(99,stock(s));
    }
    @Test void reclaimedAttemptAfterTurnCommitReusesHoldOnceAndKeepsTenMinuteDeadline()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s));AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(fail.getAndSet(false))throw new IllegalStateException("interruption after durable Lua turn");return call.callRealMethod();}).when(inventory).loadRuntimeHold(any(),anyLong());
        assertThrows(IllegalStateException.class,()->transactions.reserve(a));assertEquals(99,redisStock(s));assertEquals(100,stock(s));
        assertEquals(1,((Number)queue.get(a.get("id").toString()).get("lua_turn_finished")).intValue());
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1),a.get("id"));Map<String,Object> recovered=attempts.claim();assertEquals(a.get("id"),recovered.get("id"));
        transactions.reserve(a);assertEquals(100,stock(s),"stale owner cannot confirm");
        transactions.prepare(recovered);transactions.reserve(recovered);
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));verify(inventory,times(1)).reserveRuntime(any(),eq(1L),anyLong());
        assertEquals("RESERVED",queue.get(a.get("id").toString()).get("state"));
        Map<Object,Object> marker=redis.opsForHash().entries(com.ticketing.inventory.InventoryService.prefix(s.sku)+"reservation:"+a.get("reservation_id"));
        assertEquals(600000,Long.parseLong(marker.get("expiresAt").toString())-Long.parseLong(marker.get("heldAt").toString()));
    }
    @Test void crashBetweenLuaAndTurnCommitRetriesIdempotentLuaWithoutDoubleDeduction()throws Exception{
        isolated();Sale s=sale("PASS");Map<String,Object> a=qualify(request(s));AtomicBoolean fail=new AtomicBoolean(true);
        // Lua succeeds, then the ordered transaction fails before lua_turn_finished commits.
        doAnswer(call->{Object result=call.callRealMethod();if(fail.getAndSet(false))throw new IllegalStateException("crash after Lua before turn commit");return result;}).when(inventory).reserveRuntime(any(),anyLong(),anyLong());
        assertThrows(IllegalStateException.class,()->transactions.reserve(a));assertEquals(99,redisStock(s));
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1),a.get("id"));Map<String,Object> recovered=attempts.claim();
        transactions.prepare(recovered);transactions.reserve(recovered);
        assertEquals(99,redisStock(s),"idempotent reserve Lua returns the existing hold");assertEquals(99,stock(s));
        assertEquals("RESERVED",queue.get(a.get("id").toString()).get("state"));
    }
}
