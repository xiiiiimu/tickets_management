package com.ticketing.integration;

import com.ticketing.ticket.*;
import com.ticketing.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorRaceIT extends VectorReservationIT {
    Map<String,Object> prepare(ReserveRequest r){reliable(r);Map<String,Object> job=attempts.claim();assertNotNull(job);assertNotNull(attempts.decided(job,admission.classify(job,armory.runtimePlan(r.getEventSessionId(),r.getTicketSkuId()))));return queue.get(job.get("id").toString());}
    @Test void nonHeadSoldOutTombstoneCannotLeaveSequenceHole()throws Exception{
        isolated();Sale s=vectorSale(16,16,16);Map<String,Object> held=run(quantity(s,16));assertEquals("RESERVED",held.get("state"));
        Map<String,Object> later=prepare(request(s)),head=prepare(request(s));transactions.prepare(head);transactions.prepare(later);sweeper.signal(later);sweeper.run();
        assertEquals("SOLD_OUT",queue.get(head.get("id").toString()).get("state"));Map<String,Object> pending=queue.get(later.get("id").toString());
        if("PROCESSING".equals(pending.get("state"))){Map<String,Object> claimed=attempts.claim();assertEquals(later.get("id"),claimed.get("id"));ReflectionTestUtils.invokeMethod(service,"process",claimed);}
        assertEquals("SOLD_OUT",queue.get(later.get("id").toString()).get("state"));assertEquals(4,db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE sku_id=?",Long.class,s.sku));assertEquals(0,stock(s));assertEquals(0,redisStock(s));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));
    }
    @Test void soldOutSweepSkipsTransactionExecutingLuaAndCannotCancelItsHold()throws Exception{
        isolated();Sale s=vectorSale(16,16,16);Map<String,Object> job=prepare(quantity(s,16));transactions.prepare(job);CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        doAnswer(call->{Object result=call.callRealMethod();held.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return result;}).when(inventory).reserveRuntime(any(),anyLong(),anyLong());
        try{Future<?> reserve=pool.submit(()->transactions.reserve(job));assertTrue(held.await(3,TimeUnit.SECONDS));sweeper.signal(job);sweeper.run();assertEquals("HELD",redis.opsForHash().get(InventoryService.prefix(s.sku)+"reservation:"+job.get("reservation_id"),"state"));release.countDown();reserve.get(5,TimeUnit.SECONDS);assertEquals("RESERVED",queue.get(job.get("id").toString()).get("state"));assertEquals(0,stock(s));assertEquals(0,redisStock(s));}
        finally{release.countDown();pool.shutdownNow();}
    }
}
