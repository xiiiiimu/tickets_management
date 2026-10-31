package com.ticketing.ticket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptDiagnosticsTest {
    @Test void exactInternalCountsAndSampledStructuredLogsDoNotChangePublicReceipt()throws Exception{
        HikariDataSource source=mock(HikariDataSource.class);HikariPoolMXBean pool=mock(HikariPoolMXBean.class);when(source.getHikariPoolMXBean()).thenReturn(pool);when(pool.getActiveConnections()).thenReturn(10);when(pool.getThreadsAwaitingConnection()).thenReturn(3);
        SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptAdmissionDiagnostics d=new AttemptAdmissionDiagnostics(metrics,source);d.observe(11,8,4,512);d.workers(()->4);
        Logger logger=(Logger)org.slf4j.LoggerFactory.getLogger(AttemptAdmissionDiagnostics.class);ListAppender<ILoggingEvent> log=new ListAppender<>();log.start();logger.addAppender(log);
        ExecutorService workers=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<?>> done=new ArrayList<>();for(int i=0;i<100;i++)done.add(workers.submit(()->{try{start.await();AttemptAdmissionException e=d.dbBusy();assertEquals(429,e.status);assertEquals("SYSTEM_BUSY",e.code);assertFalse(e.getMessage().contains("DB_BUSY"));}catch(InterruptedException e){throw new AssertionError(e);}}));start.countDown();for(Future<?> f:done)f.get(5,TimeUnit.SECONDS);
            for(int i=0;i<50;i++)d.record(d.capacity("waiting_budget"));
            assertEquals(100,metrics.get("attempt.admission.rejected.internal").tags("reason","DB_BUSY","detail","pool_busy").counter().count());assertEquals(50,metrics.get("attempt.admission.rejected.internal").tags("reason","CAPACITY_REJECT","detail","waiting_budget").counter().count());
            assertEquals(2,log.list.size());for(ILoggingEvent e:log.list){String text=e.getFormattedMessage();assertTrue(text.contains("queue_waiting=11"));assertTrue(text.contains("capacity_budget=8"));assertTrue(text.contains("workers_local=4"));assertTrue(text.contains("hikari_active=10"));assertTrue(text.contains("hikari_waiting=3"));}
            verify(source,times(150)).getHikariPoolMXBean();
        }finally{workers.shutdownNow();logger.detachAppender(log);log.stop();}
    }
    @Test void unknownQueueSnapshotDoesNotInventZeroOrQueryDatabase(){
        javax.sql.DataSource source=mock(javax.sql.DataSource.class);SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptAdmissionDiagnostics d=new AttemptAdmissionDiagnostics(metrics,source);assertEquals("SYSTEM_BUSY",d.dbBusy().code);verifyNoInteractions(source);
    }
    @Test void internalDetailsNeverAppearInPublicHttpError()throws Exception{
        SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptAdmissionDiagnostics d=new AttemptAdmissionDiagnostics(metrics,mock(javax.sql.DataSource.class));
        AttemptAdmissionException refusal=d.dbBusy();ReservationV2Controller controller=new ReservationV2Controller(mock(ReservationV2Service.class),mock(ReservationIngress.class));
        org.springframework.http.ResponseEntity<com.ticketing.common.Result> response=controller.attemptError(refusal);assertEquals(429,response.getStatusCodeValue());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response.getBody());assertTrue(json.contains("SYSTEM_BUSY"));assertTrue(json.contains("retryWithSameRequestId"));assertFalse(json.contains("DB_BUSY"));assertFalse(json.contains("hikari"));
    }
}
