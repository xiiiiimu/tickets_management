package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V3BeginTimingTest {
    Db db;V3JobStarter starter;SimpleMeterRegistry metrics;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:v3-begin-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(ds);
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());db.jdbc.execute("CREATE TABLE tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY)");
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);metrics=new SimpleMeterRegistry();
        starter=new V3JobStarter(db,new ReservationDispatch(db,mock(RiskSubjectFence.class),manager),mock(ReservationAttemptStore.class),manager,metrics);
    }
    V3Redis.Message message(){
        Map<String,String> m=new HashMap<>();for(String k:Arrays.asList("requestId","attemptId","reservationId","orderId","sessionId","skuId","deviceId","ip","fingerprint"))m.put(k,k);
        m.put("v","1");m.put("userId","1");m.put("quantity","1");m.put("saleVersion","1");m.put("acceptedAt",Long.toString(System.currentTimeMillis()));m.put("deadline",Long.toString(System.currentTimeMillis()+5000));
        return new V3Redis.Message("1-0",m);
    }
    @Test void successRecordsDisjointTransactionAndSqlBoundariesWithoutChangingOwnership(){
        V3Redis.Message m=message();assertEquals(V3JobStarter.Kind.OWNED,starter.begin(m,"token").kind);
        for(String phase:Arrays.asList("total","begin","body","completion"))assertEquals(1,metrics.get("attempt.transaction.duration").tags("operation","v3_begin","outcome","success","phase",phase).timer().count());
        for(String op:Arrays.asList("lock_request","lookup_alias","lookup_legacy_intent","lock_active_owner","database_time","insert_job","read_job"))assertEquals(1,metrics.get("attempt.v3.begin.sql").tags("operation",op,"outcome","success").timer().count());
        assertEquals(1,metrics.get("attempt.v3.begin.commit_callbacks").tag("outcome","success").timer().count());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals("token",db.one("SELECT * FROM tb_reservation_job").get("lease_token"));
        assertEquals(V3JobStarter.Kind.OWNED,starter.begin(m,"token").kind);assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));
    }
    @Test void failedInsertStillRollsBackAndFailureMetricsDoNotCreateACommit(){
        db.jdbc.execute("ALTER TABLE tb_reservation_job ADD CONSTRAINT fixture_failure CHECK(quantity>1)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->starter.begin(message(),"token"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));
        assertEquals(1,metrics.get("attempt.transaction.duration").tags("operation","v3_begin","outcome","failure","phase","completion").timer().count());
        assertEquals(1,metrics.get("attempt.v3.begin.sql").tags("operation","insert_job","outcome","failure").timer().count());
        assertNull(metrics.find("attempt.v3.begin.commit_callbacks").tag("outcome","success").timer());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
