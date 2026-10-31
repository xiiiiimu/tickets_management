package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualificationClaimBoundaryTest {
    ReservationAttemptStoreTest f;
    @BeforeEach void setup(){f=new ReservationAttemptStoreTest();f.setup();new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql")).execute(f.source);}
    @Test void deferredProcessingReclaimsSameAttemptAfterOldDeadlineWithoutReturningToWaiting(){
        f.register(1);Map<String,Object> old=f.store.claim();f.now.addAndGet(31000);f.store.defer(old);
        assertEquals("PROCESSING",f.queue.get(old.get("id").toString()).get("state"));Map<String,Object> next=f.store.claim();
        assertEquals(old.get("id"),next.get("id"));assertNotEquals(old.get("lease_token"),next.get("lease_token"));assertEquals(old.get("processing_started_at"),next.get("processing_started_at"));assertFalse(f.store.heartbeat(old));
    }
    @Test void staleDeferralCannotClearNewOwnersLease(){
        f.register(1);Map<String,Object> old=f.store.claim();f.store.defer(old);Map<String,Object> current=f.store.claim();f.store.defer(old);
        assertEquals(current.get("lease_token"),f.queue.get(current.get("id").toString()).get("lease_token"));
    }
    @Test void modeMismatchRefusesStartupInsteadOfMixingOrderedAndUnorderedNodes(){
        f.db.jdbc.update("UPDATE tb_reservation_dispatch SET ordered_lua_enabled=1 WHERE id=1");assertThrows(IllegalStateException.class,f.store::initialize);
        MockEnvironment env=new MockEnvironment().withProperty("ticketing.reservations-v2.attempts.ordered-lua","true");
        new ReservationAttemptStore(f.db,f.queue,f.manager,f.metrics,env).initialize();
    }
    @Test void pendingTurnReleasesWorkerWithoutAbortOrRiskReplay(){
        ReservationAttemptEntryTest entry=new ReservationAttemptEntryTest();entry.setup();
        Map<String,Object> job=new HashMap<>();job.put("id","job");job.put("attempt_protocol",3);job.put("admission","{}");job.put("lease_token","token");
        when(entry.store.currentOwned(job)).thenReturn(job);when(entry.store.heartbeat(job)).thenReturn(true);
        ReservationV2Transactions transactions=(ReservationV2Transactions)ReflectionTestUtils.getField(entry.service,"transactions");
        doThrow(new ReservationQualificationOrder.Pending()).when(transactions).reserve(job);
        entry.service.process(job);verify(entry.store).defer(job);verify(transactions,never()).abort(any(),anyString());verifyNoInteractions(entry.risk);
    }
}
