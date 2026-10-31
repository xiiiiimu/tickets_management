package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Selected methods, fresh owner-checked schema only; no Redis or trade side effects. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="ticketing.attempts.mysql",matches="true")
class AttemptQuickMysqlTest extends ReservationAttemptMysqlTest {
    @BeforeAll static void migrateModes(){DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));Map<String,Object> before=jdbc.queryForMap("SELECT * FROM tb_reservation_dispatch");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql"),new ClassPathResource("db/migration/V112__attempt_quick_review_mode.sql")).execute(ds);Map<String,Object> after=jdbc.queryForMap("SELECT * FROM tb_reservation_dispatch");for(String key:before.keySet())assertEquals(before.get(key),after.get(key));assertEquals(0,((Number)after.get("quick_review_enabled")).intValue());}
    @Test void quickModeRequiresExplicitCompatibleActivation(){assertEquals(0,db.jdbc.queryForObject("SELECT quick_review_enabled FROM tb_reservation_dispatch",Integer.class));db.jdbc.update("UPDATE tb_reservation_dispatch SET ordered_lua_enabled=1,quick_review_enabled=1");try{assertThrows(IllegalStateException.class,first::initialize);MockEnvironment active=new MockEnvironment().withProperty("spring.datasource.url",System.getenv("SPRING_DATASOURCE_URL")).withProperty("ticketing.reservations-v2.attempts.ordered-lua","true").withProperty("ticketing.reservations-v2.attempts.quick-review","true");new ReservationAttemptStore(db,queue,manager,metrics,active).initialize();}finally{db.jdbc.update("UPDATE tb_reservation_dispatch SET ordered_lua_enabled=0,quick_review_enabled=0");}}
    @Test void concurrentDecisionsPersistOneOutcomeAndOneAudit()throws Exception{
        register(1);Map<String,Object> claimed=first.claim();ReservationAttemptStore other=store(db);CyclicBarrier barrier=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        Map<String,Object> agree=new HashMap<>();agree.put("decision","REVIEW");agree.put("quickDecision","AGREE");agree.put("resultCode","REVIEW");Map<String,Object> reject=new HashMap<>();reject.put("decision","BLOCK");reject.put("quickDecision","REJECT");reject.put("resultCode","RISK_REJECTED");
        try{Future<?> a=pool.submit(()->{await(barrier);first.decided(claimed,agree);}),b=pool.submit(()->{await(barrier);other.decided(claimed,reject);});a.get(6,TimeUnit.SECONDS);b.get(6,TimeUnit.SECONDS);Map<String,Object> finalJob=queue.get(claimed.get("id").toString());assertTrue(Arrays.asList("PROCESSING","REJECTED").contains(finalJob.get("state")));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_risk_audit WHERE job_id=?",Integer.class,claimed.get("id")));assertNotNull(finalJob.get("admission"));}
        finally{pool.shutdownNow();}
    }
    static void await(CyclicBarrier barrier){try{barrier.await(3,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}}
}
