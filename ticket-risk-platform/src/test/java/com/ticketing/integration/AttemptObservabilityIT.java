package com.ticketing.integration;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.ticket.*;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Two low-volume owned-DB cases; real Redis/Armory/transactions, no external Agent or payment. */
class AttemptObservabilityIT extends VectorReservationIT {
    @Autowired ConfigurableApplicationContext context;
    @Autowired MeterRegistry metrics;
    @Autowired AttemptWorkerReadiness productionReadiness;
    @SuppressWarnings("unchecked") AttemptWorkerReadiness freshGuard(){
        ObjectProvider<ReservationAttemptStore> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(attempts);
        return new AttemptWorkerReadiness(db,redis,armory,provider,metrics,manager,context);
    }
    void event(AttemptWorkerReadiness guard){guard.applicationReady(new ApplicationReadyEvent(new SpringApplication(TicketPlatformApplication.class),new String[0],context));}
    long count(String operation){io.micrometer.core.instrument.Timer timer=metrics.find("attempt.transaction.duration").tags("operation",operation,"outcome","success","phase","total").timer();return timer==null?0:timer.count();}
    void assertParts(String operation){double sum=0;for(String part:Arrays.asList("begin","body","completion"))sum+=metrics.get("attempt.transaction.duration").tags("operation",operation,"outcome","success","phase",part).timer().totalTime(TimeUnit.NANOSECONDS);assertEquals(metrics.get("attempt.transaction.duration").tags("operation",operation,"outcome","success","phase","total").timer().totalTime(TimeUnit.NANOSECONDS),sum,1.0);}
    @Test void missingReadyManifestBlocksRecoveryClaimThenRestorationCompletesExactlyOnce()throws Exception{
        isolated();assertTrue(productionReadiness.isReady());Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);reliable(r);Map<String,Object> first=attempts.claim();assertNotNull(first);
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1000),first.get("id"));
        AttemptWorkerReadiness fresh=freshGuard();Object oldStore=ReflectionTestUtils.getField(attempts,"readiness"),oldService=ReflectionTestUtils.getField(service,"attemptReadiness");
        String key=TicketSaleArmory.prefix(s.session)+"manifest:v1",manifest=redis.opsForValue().get(key);assertNotNull(manifest);
        long claims=count("claim"),prepares=count("prepare");
        try{
            ReflectionTestUtils.setField(attempts,"readiness",fresh);ReflectionTestUtils.setField(service,"attemptReadiness",fresh);
            assertNull(attempts.claim());redis.delete(key);event(fresh);assertFalse(fresh.isReady());assertNull(attempts.claim());assertEquals(claims,count("claim"));assertEquals(16,stock(s));assertEquals(16,redisStock(s));
            AttemptAdmissionException refused=assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(quantity(s,1),"ip"));assertEquals(503,refused.status);assertEquals("SYSTEM_BUSY",refused.code);
            redis.opsForValue().set(key,manifest);ReflectionTestUtils.setField(fresh,"nextProbe",0L);assertTrue(fresh.ensureReady());Map<String,Object> recovered=attempts.claim();assertNotNull(recovered);assertNotEquals(first.get("lease_token"),recovered.get("lease_token"));
            ReflectionTestUtils.invokeMethod(service,"process",recovered);assertEquals("RESERVED",queue.byRequest(r.getRequestId()).get("state"));assertEquals(11,stock(s));assertEquals(11,redisStock(s));assertEquals(claims+1,count("claim"));assertEquals(prepares+1,count("prepare"));
            for(String operation:Arrays.asList("register","claim","prepare"))assertParts(operation);
            assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));verifyNoInteractions(agent);
        }finally{redis.opsForValue().set(key,manifest);ReflectionTestUtils.setField(attempts,"readiness",oldStore);ReflectionTestUtils.setField(service,"attemptReadiness",oldService);}
    }
    @Test void startupReadyDoesNotBypassLaterArmoryFailureOrMutateStock()throws Exception{
        isolated();assertTrue(productionReadiness.isReady());Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);reliable(r);Map<String,Object> claimed=attempts.claim();assertNotNull(claimed);
        String key=TicketSaleArmory.prefix(s.session)+"manifest:v1",manifest=redis.opsForValue().get(key);redis.delete(key);
        try{ReflectionTestUtils.invokeMethod(service,"process",claimed);assertEquals("FAILED",queue.byRequest(r.getRequestId()).get("state"));assertEquals(16,stock(s));assertEquals(16,redisStock(s));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));}
        finally{redis.opsForValue().set(key,manifest);}
    }
}
