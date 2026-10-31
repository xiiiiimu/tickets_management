package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AttemptQuickModeTest {
    ReservationAttemptStoreTest f;
    @BeforeEach void setup(){f=new ReservationAttemptStoreTest();f.setup();new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql"),new ClassPathResource("db/migration/V112__attempt_quick_review_mode.sql")).execute(f.source);}
    @Test void migrationDefaultsOffAndOldStoreStillInitializes(){assertEquals(0,f.db.jdbc.queryForObject("SELECT quick_review_enabled FROM tb_reservation_dispatch",Integer.class));f.store.initialize();}
    @Test void quickReviewCannotStartWithoutOrderedQualification(){assertThrows(IllegalStateException.class,()->new ReservationAttemptStore(f.db,f.queue,f.manager,f.metrics,new MockEnvironment().withProperty("ticketing.reservations-v2.attempts.quick-review","true")));}
    @Test void incompatibleNodesCannotMixQuickAndOldDecisions(){f.db.jdbc.update("UPDATE tb_reservation_dispatch SET ordered_lua_enabled=1,quick_review_enabled=1");assertThrows(IllegalStateException.class,f.store::initialize);MockEnvironment env=new MockEnvironment().withProperty("ticketing.reservations-v2.attempts.ordered-lua","true").withProperty("ticketing.reservations-v2.attempts.quick-review","true");new ReservationAttemptStore(f.db,f.queue,f.manager,f.metrics,env).initialize();}
    @Test void unavailableAndRiskRejectedViewsAreDistinctAndTerminal(){java.util.Map<String,Object> job=f.register(1);f.db.jdbc.update("UPDATE tb_reservation_job SET state='FAILED',result_code='PROFILE_UNAVAILABLE',active_owner=NULL WHERE id=?",job.get("id"));java.util.Map<String,Object> view=f.queue.view(f.queue.get(job.get("id").toString()),1);assertEquals(true,view.get("validationUnavailable"));assertEquals(true,view.get("retryWithNewAttempt"));assertEquals(true,view.get("terminal"));assertEquals(false,view.get("reservationSuccess"));f.db.jdbc.update("UPDATE tb_reservation_job SET state='REJECTED',result_code='RISK_REJECTED' WHERE id=?",job.get("id"));view=f.queue.view(f.queue.get(job.get("id").toString()),1);assertEquals("RISK_REJECTED",view.get("resultCode"));assertFalse(view.containsKey("retryable"));}
}
