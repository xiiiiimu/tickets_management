package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.TaskPools;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.sale.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Wiring of gate, registrar, configurable workers and batch claim into the existing entry/dispatch. */
class AttemptThroughputEntryTest {
    ReservationDispatch queue=mock(ReservationDispatch.class);ReservationAttemptStore store=mock(ReservationAttemptStore.class);
    TicketSaleArmory armory=mock(TicketSaleArmory.class);RiskDecisionService risk=mock(RiskDecisionService.class);TaskPools pools=mock(TaskPools.class);
    ReservationIntentService intents=mock(ReservationIntentService.class);ReservationV2Service service;SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    @BeforeEach void setup(){
        service=new ReservationV2Service(queue,armory,risk,intents,mock(ReservationV2Transactions.class),pools,metrics);
        ReflectionTestUtils.setField(service,"attempts",store);ReflectionTestUtils.setField(service,"accepting",true);
        when(intents.fingerprint(any())).thenReturn("fp");when(queue.byRequest(anyString())).thenReturn(null);when(store.dependenciesAvailable()).thenReturn(true);
    }
    ReserveRequest request(){ReserveRequest r=new ReserveRequest();r.setUserId(7L);r.setRequestId("request");r.setEventSessionId("session");r.setTicketSkuId("sku");r.setQuantity(1);r.setDeviceId("device");return r;}
    int localWorkers(){return ((AtomicInteger)ReflectionTestUtils.getField(service,"attemptWorkers")).get();}
    Map<String,Object> job(String id){Map<String,Object> j=new HashMap<>();j.put("id",id);j.put("lease_token","t-"+id);return j;}

    @Test void gateRefusalHappensBeforeAnyDatabaseRead(){
        AttemptAdmissionGate gate=mock(AttemptAdmissionGate.class);when(gate.admit("request")).thenReturn(new AttemptAdmissionGate.Hold(gate,"request","t",true,false));
        when(store.gateRejected()).thenReturn(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        ReflectionTestUtils.setField(service,"admissionGate",gate);
        AttemptAdmissionException refused=assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));
        assertEquals(429,refused.status);assertEquals("SYSTEM_BUSY",refused.code);
        verify(queue,never()).byRequest(anyString());verifyNoInteractions(armory,risk,pools);verify(store,never()).register(any(),any(),any(),anyLong());
        assertEquals(1.0,metrics.get("attempt.admission.rejected").tag("reason","system_busy").counter().count());
    }
    @Test void gatePassContinuesWithTheUnchangedPath(){
        AttemptAdmissionGate gate=mock(AttemptAdmissionGate.class);AttemptAdmissionGate.Hold hold=new AttemptAdmissionGate.Hold(gate,"request","t",false,true);when(gate.admit("request")).thenReturn(hold);ReflectionTestUtils.setField(service,"admissionGate",gate);
        when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        Map<String,Object> job=new HashMap<>();job.put("id","attempt");job.put("newlyCreated",true);
        when(store.register(any(),eq("ip"),eq("fp"),eq(1L))).thenReturn(job);when(queue.view(job,7)).thenReturn(new LinkedHashMap<>());
        assertEquals("attempt",service.registerAttempt(request(),"ip").get("attemptId"));
        verify(queue).byRequest("request");
        verify(gate).settleCommitted(hold,true);verify(gate,never()).release(any());
    }
    @Test void registrarReplacesSingleRegisterWhenEnabled(){
        AttemptRegistrar registrar=mock(AttemptRegistrar.class);ReflectionTestUtils.setField(service,"registrar",registrar);
        when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        Map<String,Object> job=new HashMap<>();job.put("id","batched");job.put("newlyCreated",true);
        when(registrar.register(any(),eq("ip"),eq("fp"),eq(1L),any())).thenReturn(job);when(queue.view(job,7)).thenReturn(new LinkedHashMap<>());
        assertEquals("batched",service.registerAttempt(request(),"ip").get("attemptId"));
        verify(store,never()).register(any(),any(),any(),anyLong());
    }
    @Test void registrarUnknownCommitStillMapsToRetryable503(){
        AttemptRegistrar registrar=mock(AttemptRegistrar.class);ReflectionTestUtils.setField(service,"registrar",registrar);
        when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        when(registrar.register(any(),anyString(),anyString(),anyLong(),any())).thenThrow(new org.springframework.transaction.TransactionSystemException("commit unknown"));
        AttemptAdmissionException result=assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));
        assertEquals(503,result.status);assertEquals("VALIDATION_UNAVAILABLE",result.code);
    }
    @Test void configuredWorkerCountBoundsSingleClaimWorkers(){
        ReflectionTestUtils.setField(service,"maxAttemptWorkers",6);when(store.availableHint(6)).thenReturn(6);
        List<Runnable> tasks=new ArrayList<>();doAnswer(a->{tasks.add(a.getArgument(1));return null;}).when(pools).execute(eq("sale"),any());
        service.dispatchOnce();
        assertEquals(6,tasks.size());assertEquals(6,localWorkers());
        service.dispatchOnce();assertEquals(6,tasks.size(),"no more than the configured local slots");
    }
    @Test void batchClaimGrantsInOneCallAndDefersAGrantThePoolRefuses(){
        ReflectionTestUtils.setField(service,"maxAttemptWorkers",3);ReflectionTestUtils.setField(service,"attemptBatchClaim",true);
        when(store.availableHint(3)).thenReturn(3);
        Map<String,Object> a=job("a"),b=job("b");when(store.claimBatch(3)).thenReturn(Arrays.asList(a,b));
        AtomicReference<Runnable> started=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        doAnswer(x->{if(calls.incrementAndGet()==1){started.set(x.getArgument(1));return null;}throw new RejectedExecutionException("full");}).when(pools).execute(eq("sale"),any());
        service.dispatchOnce();
        verify(store,times(1)).claimBatch(3);verify(store,never()).claim();
        verify(store).defer(b);verify(store,never()).defer(a);
        assertEquals(1,localWorkers(),"one unused reservation released, one refused grant released");
        assertEquals(1.0,metrics.get("attempt.worker.rejected").counter().count());
        started.get().run(); // currentOwned returns null on the mock -> worker exits and frees its slot
        assertEquals(0,localWorkers());
    }
    @Test void batchClaimFailureReleasesEveryReservedSlot(){
        ReflectionTestUtils.setField(service,"maxAttemptWorkers",4);ReflectionTestUtils.setField(service,"attemptBatchClaim",true);
        when(store.availableHint()).thenReturn(4);when(store.claimBatch(4)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        service.dispatchOnce();assertEquals(0,localWorkers());verifyNoInteractions(pools);
    }
    @Test void invalidWorkerCountFailsFast(){
        ReflectionTestUtils.setField(service,"maxAttemptWorkers",0);
        assertThrows(IllegalArgumentException.class,()->service.startDispatchControl());
    }

    // ---- gate settlement owned by the entry before hand-off, by the registrar after it ----
    AttemptAdmissionGate gate;AttemptAdmissionGate.Hold hold;
    void reservedGate(){gate=mock(AttemptAdmissionGate.class);hold=new AttemptAdmissionGate.Hold(gate,"request","t",false,true);when(gate.admit("request")).thenReturn(hold);ReflectionTestUtils.setField(service,"admissionGate",gate);}
    @Test void refusalBeforeHandOffReleasesTheGateSlot(){
        reservedGate();ReflectionTestUtils.setField(service,"accepting",false);
        assertEquals(503,assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip")).status);
        verify(gate).release(hold);
    }
    @Test void refusalInsideTheSingleRegisterTransactionReleasesTheGateSlot(){
        reservedGate();when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        when(store.register(any(),any(),any(),anyLong())).thenThrow(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));
        verify(gate).release(hold);
    }
    @Test void unknownSingleRegisterOutcomeKeepsTheGateSlot(){
        reservedGate();when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        when(store.register(any(),any(),any(),anyLong())).thenThrow(new org.springframework.dao.QueryTimeoutException("commit?"));
        assertEquals("VALIDATION_UNAVAILABLE",assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip")).code);
        verify(gate,never()).release(any());verify(gate,never()).settleCommitted(any(),anyBoolean());
    }
    @Test void existingReceiptSettlesWithoutNewWaitingRow(){
        reservedGate();Map<String,Object> previous=new HashMap<>();previous.put("id","old");previous.put("state","RESERVED");
        when(queue.byRequest("request")).thenReturn(previous);when(queue.view(previous,7)).thenReturn(new LinkedHashMap<>());
        assertEquals(false,service.registerAttempt(request(),"ip").get("newSubmission"));
        verify(gate).settleCommitted(hold,false);verify(gate,never()).release(any());
    }
    @Test void afterHandOffOnlyTheRegistrarSettles(){
        reservedGate();AttemptRegistrar registrar=mock(AttemptRegistrar.class);ReflectionTestUtils.setField(service,"registrar",registrar);
        when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        when(registrar.register(any(),anyString(),anyString(),anyLong(),any())).thenThrow(new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","unknown"));
        assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));
        verify(registrar).register(any(),eq("ip"),eq("fp"),eq(1L),same(hold));
        verify(gate,never()).release(any());verify(gate,never()).settleCommitted(any(),anyBoolean());
    }
}
