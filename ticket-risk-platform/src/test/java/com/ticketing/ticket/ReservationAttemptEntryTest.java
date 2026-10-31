package com.ticketing.ticket;

import com.ticketing.common.context.UserHolder;
import com.ticketing.common.dto.UserDTO;
import com.ticketing.infrastructure.TaskPools;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.sale.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.sql.Timestamp;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationAttemptEntryTest {
    ReservationDispatch queue=mock(ReservationDispatch.class);ReservationAttemptStore store=mock(ReservationAttemptStore.class);
    TicketSaleArmory armory=mock(TicketSaleArmory.class);RiskDecisionService risk=mock(RiskDecisionService.class);TaskPools pools=mock(TaskPools.class);
    ReservationIntentService intents=mock(ReservationIntentService.class);ReservationV2Service service;
    @BeforeEach void setup(){service=new ReservationV2Service(queue,armory,risk,intents,mock(ReservationV2Transactions.class),pools,new SimpleMeterRegistry());ReflectionTestUtils.setField(service,"attempts",store);ReflectionTestUtils.setField(service,"accepting",true);when(queue.byRequest(anyString())).thenReturn(null);when(store.claim()).thenReturn(null);when(store.availableHint()).thenReturn(1);when(store.dependenciesAvailable()).thenReturn(true);when(intents.fingerprint(any())).thenReturn("fp");}
    @AfterEach void clear(){UserHolder.removeUser();}
    ReserveRequest request(){ReserveRequest r=new ReserveRequest();r.setUserId(7L);r.setRequestId("request");r.setEventSessionId("session");r.setTicketSkuId("sku");r.setQuantity(1);r.setDeviceId("device");return r;}
    @Test void receiptIsBuiltAfterCommittedRegistrationWithoutRiskOrInventory(){
        Map<String,Object> job=new HashMap<>();job.put("id","attempt");job.put("user_id",7L);job.put("newlyCreated",true);
        SaleManifest plan=new SaleManifest("{\"version\":1}",new ObjectMapper());when(armory.runtimePlan("session","sku")).thenReturn(plan);
        when(store.register(any(),eq("ip"),eq("fp"),eq(1L))).thenReturn(job);
        when(queue.view(job,7)).thenReturn(new LinkedHashMap<>(Collections.singletonMap("state","WAITING")));
        Map<String,Object> result=service.registerAttempt(request(),"ip");assertEquals(true,result.get("reliableAccepted"));assertEquals("attempt",result.get("attemptId"));
        org.mockito.InOrder order=inOrder(store,queue);order.verify(store).dependenciesAvailable();order.verify(store).register(any(),eq("ip"),eq("fp"),eq(1L));order.verify(queue).view(job,7);
        verifyNoInteractions(risk,pools);
    }
    @Test void commitFailureReturnsRetryableUnavailableInsteadOfReceipt(){
        when(armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1}",new ObjectMapper()));
        when(store.register(any(),anyString(),anyString(),anyLong())).thenThrow(new org.springframework.transaction.TransactionSystemException("commit unknown"));
        AttemptAdmissionException result=assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));assertEquals(503,result.status);assertEquals("VALIDATION_UNAVAILABLE",result.code);verifyNoInteractions(risk,pools);
    }
    @Test void databaseReadFailureNeverUsesDefaultIdentityOrReturns202(){when(queue.byRequest("request")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));assertEquals(503,assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip")).status);verifyNoInteractions(risk,pools,armory);}
    @Test void poolRejectionNeverClaimsOrExecutesInCaller(){doThrow(new RejectedExecutionException("full")).when(pools).execute(eq("sale"),any());service.dispatchOnce();verify(store).expireWaiting();verify(store,never()).claim();verifyNoInteractions(risk);}
    @Test void emptyQueueDoesNotSpinClaimWorkers(){when(store.availableHint()).thenReturn(0);service.dispatchOnce();verifyNoInteractions(pools,risk);verify(store,never()).claim();}
    @Test void claimHappensOnlyAfterWorkerActuallyStarts(){AtomicReference<Runnable> task=new AtomicReference<>();doAnswer(a->{task.compareAndSet(null,a.getArgument(1));return null;}).when(pools).execute(eq("sale"),any());service.dispatchOnce();verify(store,never()).claim();assertNotNull(task.get());task.get().run();verify(store).claim();}
    @Test void durableModeDoesNotTouchVolatileQueue(){ReservationIngress ingress=new ReservationIngress(service,intents,new SimpleMeterRegistry(),new MockEnvironment());
        try{ReflectionTestUtils.setField(service,"attempts",store);when(queue.byRequest("request")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));assertThrows(AttemptAdmissionException.class,()->ingress.receive(request(),"ip"));java.util.concurrent.ThreadPoolExecutor executor=(java.util.concurrent.ThreadPoolExecutor)ReflectionTestUtils.getField(ingress,"executor");assertEquals(0,executor.getTaskCount());}finally{ingress.close();}}
    @Test void controllerBusyResponseHasExplicitCodeAndNoReliableReceipt(){ReservationV2Controller controller=new ReservationV2Controller(service,mock(ReservationIngress.class));org.springframework.http.ResponseEntity<com.ticketing.common.Result> response=controller.attemptError(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));assertEquals(429,response.getStatusCodeValue());assertEquals(false,response.getBody().getSuccess());assertEquals("SYSTEM_BUSY",((Map<?,?>)response.getBody().getData()).get("code"));}
    @Test void legacyEndpointCannotTakeOverAnAttempt(){
        com.ticketing.infrastructure.Db db=mock(com.ticketing.infrastructure.Db.class);com.ticketing.inventory.InventoryService inventory=mock(com.ticketing.inventory.InventoryService.class);ReservationTransactionService old=mock(ReservationTransactionService.class);
        TicketController controller=new TicketController(risk,intents,old,db,inventory,new SimpleMeterRegistry());ReflectionTestUtils.setField(controller,"durableAttempts",true);
        UserDTO user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);
        when(db.one(contains("attempt_protocol=3"),eq("request"),eq("request"))).thenReturn(Collections.singletonMap("id","managed"));
        assertEquals(409,assertThrows(com.ticketing.common.BusinessException.class,()->controller.reserve(request(),new MockHttpServletRequest())).status);verifyNoInteractions(inventory,old,risk);
    }
    @Test void databaseBusyKeepsPublicCodeAndSeparatelyCountsInternalReason(){
        SimpleMeterRegistry metrics=(SimpleMeterRegistry)ReflectionTestUtils.getField(service,"metrics");AttemptAdmissionDiagnostics diagnostic=new AttemptAdmissionDiagnostics(metrics,mock(javax.sql.DataSource.class));
        when(store.dependenciesAvailable()).thenReturn(false);when(store.dbBusy()).thenAnswer(a->diagnostic.dbBusy());
        AttemptAdmissionException refusal=assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip"));assertEquals(429,refusal.status);assertEquals("SYSTEM_BUSY",refusal.code);
        assertEquals(1,metrics.get("attempt.admission.rejected.internal").tags("reason","DB_BUSY").counter().count());assertEquals(1,metrics.get("attempt.admission.rejected").tag("reason","system_busy").counter().count());verify(store).dbBusy();verify(store,never()).register(any(),anyString(),anyString(),anyLong());verifyNoInteractions(armory,risk,pools);
    }
    @Test void startupBlocksNewRegistrationButExistingReceiptRemainsIdempotent(){
        AttemptWorkerReadiness guard=mock(AttemptWorkerReadiness.class);ReflectionTestUtils.setField(service,"attemptReadiness",guard);
        assertEquals(503,assertThrows(AttemptAdmissionException.class,()->service.registerAttempt(request(),"ip")).status);
        Map<String,Object> existing=new HashMap<>();existing.put("id","existing");existing.put("user_id",7L);existing.put("attempt_protocol",3);existing.put("state","PROCESSING");when(queue.byRequest("request")).thenReturn(existing);when(queue.view(existing,7)).thenReturn(new LinkedHashMap<>(Collections.singletonMap("state","PROCESSING")));
        Map<String,Object> receipt=service.registerAttempt(request(),"ip");assertEquals(true,receipt.get("reliableAccepted"));assertEquals("existing",receipt.get("attemptId"));verify(store,never()).register(any(),anyString(),anyString(),anyLong());verifyNoInteractions(armory,risk,pools);
    }
    @Test void startupGatePreventsWorkerSubmission(){AttemptWorkerReadiness guard=mock(AttemptWorkerReadiness.class);ReflectionTestUtils.setField(service,"attemptReadiness",guard);service.dispatchOnce();verify(guard).ensureReady();verifyNoInteractions(pools,risk);verify(store,never()).claim();}
}
