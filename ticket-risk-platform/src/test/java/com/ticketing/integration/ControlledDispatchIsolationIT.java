package com.ticketing.integration;

import com.ticketing.ticket.*;
import com.ticketing.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One explicit test: all switches enabled, no manual dispatch, no payment or fulfillment. */
@TestPropertySource(properties={"ticketing.reservations-v2.dispatch.isolated=true","ticketing.reservations-v2.dispatch.batch-claim=true","ticketing.reservations-v2.dispatch.wakeup=true"})
class ControlledDispatchIsolationIT extends ReservationV2IT {
    @Autowired ReservationDispatchScheduler control;
    @Test void committedDecisionWakesIsolatedControlAndCreatesOneReliableHold()throws Exception{
        assertTrue(control.isIsolated());Sale s=sale("PASS");ReserveRequest r=request(s);enqueue(r);
        long until=System.currentTimeMillis()+15000;Map<String,Object> job;
        do{job=queue.byRequest(r.getRequestId());if("RESERVED".equals(job.get("state")))break;Thread.sleep(25);}while(System.currentTimeMillis()<until);
        assertEquals("RESERVED",job.get("state"));assertEquals(99,stock(s));assertEquals(99,redisStock(s));
        String marker=InventoryService.prefix(s.sku)+"reservation:"+job.get("reservation_id");
        long held=Long.parseLong(redis.opsForHash().get(marker,"heldAt").toString()),expires=Long.parseLong(redis.opsForHash().get(marker,"expiresAt").toString());assertEquals(600000,expires-held);
        assertEquals(job.get("reservation_id"),enqueue(r).get("reservationId"));assertEquals(99,stock(s));assertEquals(99,redisStock(s));
        assertEquals("WAITING_PAYMENT",db.one("SELECT status FROM tb_ticket_order WHERE id=?",job.get("order_id")).get("status"));verifyNoInteractions(agent);
    }
}
