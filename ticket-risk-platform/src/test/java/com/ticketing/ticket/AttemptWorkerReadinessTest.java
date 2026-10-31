package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptWorkerReadinessTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);Db db=new Db(jdbc,new ObjectMapper());StringRedisTemplate redis=mock(StringRedisTemplate.class);
    TicketSaleArmory armory=mock(TicketSaleArmory.class);ReservationAttemptStore store=mock(ReservationAttemptStore.class);
    @SuppressWarnings("unchecked") ObjectProvider<ReservationAttemptStore> provider=mock(ObjectProvider.class);
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);ConfigurableApplicationContext context=mock(ConfigurableApplicationContext.class);
    SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptWorkerReadiness ready;
    @BeforeEach void setup(){
        when(jdbc.queryForObject("SELECT 1",Integer.class)).thenReturn(1);doReturn("PONG").when(redis).execute(any(RedisCallback.class));when(provider.getObject()).thenReturn(store);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());ready=new AttemptWorkerReadiness(db,redis,armory,provider,metrics,manager,context);
    }
    void applicationReady(){ApplicationReadyEvent event=mock(ApplicationReadyEvent.class);when(event.getApplicationContext()).thenReturn(context);ready.applicationReady(event);}
    @Test void noDependencyProbeAndNoReadyBeforeOwnApplicationEvent(){assertFalse(ready.ensureReady());verifyNoInteractions(jdbc,redis,store,armory);ApplicationReadyEvent other=mock(ApplicationReadyEvent.class);ready.applicationReady(other);assertFalse(ready.ensureReady());}
    @Test void successfulProbeChecksProtocolAndAllowsNoSaleAtStartup(){applicationReady();assertTrue(ready.ensureReady());assertTrue(ready.isReady());assertTrue(ready.ensureReady());verify(jdbc,times(1)).queryForObject("SELECT 1",Integer.class);verify(store,times(1)).initialize();verifyNoInteractions(armory);}
    @Test void unavailableDatabaseDoesNotTouchRedisOrProtocolAndRetryIsThrottled(){when(jdbc.queryForObject("SELECT 1",Integer.class)).thenThrow(new IllegalStateException("db down"));applicationReady();assertFalse(ready.ensureReady());assertFalse(ready.ensureReady());verifyNoInteractions(redis,store);assertEquals(1,metrics.get("attempt.worker.readiness").tags("stage","database","result","unavailable").counter().count());}
    @Test void redisFailureDoesNotMarkProtocolReadyAndCanRecoverAfterBoundedRetry(){doThrow(new IllegalStateException("redis down")).when(redis).execute(any(RedisCallback.class));applicationReady();assertFalse(ready.ensureReady());verifyNoInteractions(store);doReturn("PONG").when(redis).execute(any(RedisCallback.class));ReflectionTestUtils.setField(ready,"nextProbe",0L);assertTrue(ready.ensureReady());}
    @Test void protocolMismatchBlocksArmoryAndWorkerReadiness(){doThrow(new IllegalStateException("mode mismatch")).when(store).initialize();applicationReady();assertFalse(ready.ensureReady());assertFalse(ready.isReady());verifyNoInteractions(armory);}
    @Test void readyRecoverySkuIsWarmedOutsideClaimTransactionAndMissingManifestBlocks(){
        Map<String,Object> job=new HashMap<>();job.put("session_id","s");job.put("sku_id","sku");job.put("active_version",2L);
        when(jdbc.queryForList(startsWith("SELECT DISTINCT"))).thenReturn(Collections.singletonList(job));when(armory.runtimeSku("s","sku",2)).thenThrow(new IllegalStateException("manifest missing"));
        applicationReady();assertFalse(ready.ensureReady());verify(manager).rollback(any());doReturn(new TicketSku()).when(armory).runtimeSku("s","sku",2);ReflectionTestUtils.setField(ready,"nextProbe",0L);assertTrue(ready.ensureReady());verify(manager).commit(any());
    }
}
