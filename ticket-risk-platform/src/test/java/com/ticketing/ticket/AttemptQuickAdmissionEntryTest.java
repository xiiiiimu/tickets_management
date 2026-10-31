package com.ticketing.ticket;

import com.ticketing.risk.AttemptRiskAdmission;
import com.ticketing.sale.SaleManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AttemptQuickAdmissionEntryTest {
    ReservationAttemptEntryTest f;AttemptRiskAdmission admission;ReservationV2Transactions transactions;Map<String,Object> job;
    @BeforeEach void setup(){f=new ReservationAttemptEntryTest();f.setup();admission=mock(AttemptRiskAdmission.class);ReflectionTestUtils.setField(f.service,"quickAdmission",admission);transactions=(ReservationV2Transactions)ReflectionTestUtils.getField(f.service,"transactions");job=new HashMap<>();job.put("id","job");job.put("attempt_protocol",3);job.put("lease_token","token");job.put("session_id","session");job.put("sku_id","sku");job.put("sale_version",1L);when(f.store.currentOwned(job)).thenReturn(job);when(f.store.heartbeat(job)).thenReturn(true);when(f.armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1,\"sessionId\":\"session\"}",new ObjectMapper()));}
    @Test void newAttemptUsesQuickAdmissionAndDoesNotCollectHistoricalFeatures(){Map<String,Object> d=new HashMap<>();d.put("decision","REVIEW");d.put("policy",new HashMap<>());when(admission.classify(eq(job),any())).thenReturn(d);when(f.store.decided(job,d)).thenReturn(job);f.service.process(job);verify(admission).classify(eq(job),any());verifyNoInteractions(f.risk);org.mockito.InOrder order=inOrder(f.store,transactions);order.verify(f.store).currentOwned(job);order.verify(f.store).decided(job,d);order.verify(f.store).heartbeat(job);order.verify(transactions).prepare(job);order.verify(transactions).reserve(job);}
    @Test void committedAdmissionIsNotExecutedAgainOnRecovery(){job.put("admission","{}");f.service.process(job);verifyNoInteractions(admission,f.risk);verify(transactions).reserve(job);}
    @Test void unavailableValidationProducesRetryableFailureWithoutIntentOrInventory(){when(admission.classify(eq(job),any())).thenThrow(new AttemptRiskAdmission.Unavailable("PROFILE_UNAVAILABLE",null));f.service.process(job);verify(transactions).abort(job,"PROFILE_UNAVAILABLE");verify(transactions,never()).prepare(any());verify(transactions,never()).reserve(any());verify(f.store,never()).decided(any(),any());verifyNoInteractions(f.risk);}
    @Test void configuredQuickModeCannotSilentlyFallBackToOldRisk(){ReflectionTestUtils.setField(f.service,"quickAdmission",null);ReflectionTestUtils.setField(f.service,"requireQuickAdmission",true);assertThrows(IllegalStateException.class,f.service::startDispatchControl);}
    @Test void defaultOffPreservesExistingRiskBranch(){ReflectionTestUtils.setField(f.service,"quickAdmission",null);job.put("user_id",7L);job.put("device_id","dev");job.put("ip","ip");job.put("request_id","request");when(f.armory.runtimePlan("session","sku")).thenReturn(new SaleManifest("{\"version\":1,\"sessionId\":\"session\",\"rules\":[]}",new ObjectMapper()));Map<String,Object> d=new HashMap<>();d.put("decision","PASS");d.put("policy",new HashMap<>());when(f.risk.classifyReservation(eq(7L),eq("dev"),eq("ip"),anyList(),eq("request"))).thenReturn(d);when(f.store.decided(job,d)).thenReturn(job);f.service.process(job);verify(f.risk).classifyReservation(eq(7L),eq("dev"),eq("ip"),anyList(),eq("request"));verifyNoInteractions(admission);verify(transactions).reserve(job);}
}
