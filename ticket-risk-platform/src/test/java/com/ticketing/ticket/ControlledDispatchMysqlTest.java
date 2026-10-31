package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CONTROLLED_TEST_URL",matches="jdbc:mysql:.*")
class ControlledDispatchMysqlTest extends ReservationDispatchBatchTest {
    @Override @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("CONTROLLED_TEST_URL"),System.getenv("CONTROLLED_TEST_USER"),System.getenv("CONTROLLED_TEST_PASSWORD"));
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());manager=new DataSourceTransactionManager(ds);
        String schema=db.jdbc.queryForObject("SELECT DATABASE()",String.class);
        if(schema==null||!schema.startsWith("credit_risk_controlled_"))throw new IllegalStateException("Explicit disposable schema required");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql")).execute(ds);
        db.jdbc.execute("CREATE TABLE IF NOT EXISTS tb_reserve_request(request_id VARCHAR(80) PRIMARY KEY)");
        db.jdbc.execute("CREATE TABLE IF NOT EXISTS tb_ticket_order(id VARCHAR(36) PRIMARY KEY,status VARCHAR(24),expires_at TIMESTAMP(3))");
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='tb_reservation_job' AND CONSTRAINT_NAME='controlled_renew_failure'",Integer.class)>0)
            db.jdbc.execute("ALTER TABLE tb_reservation_job DROP CHECK controlled_renew_failure");
        for(String table:new String[]{"tb_reservation_request_alias","tb_reservation_risk_audit","tb_reservation_job","tb_reservation_dispatch","tb_risk_subject_guard","tb_reserve_request","tb_ticket_order"})db.jdbc.update("DELETE FROM "+table);
        fences=new RiskSubjectFence(db,mock(StringRedisTemplate.class));queue=instance(10,2);
        assertTrue(Math.abs(db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).getTime()-System.currentTimeMillis())<2000,"Test clocks must agree");
    }
    @Test void twoIndependentJvmsCompeteWithoutDuplicateClaims()throws Exception{
        for(int n=1;n<=6;n++)enqueue(n,"PASS");
        db.jdbc.execute("CREATE TABLE IF NOT EXISTS tb_controlled_claim_barrier(id VARCHAR(20) PRIMARY KEY)");db.jdbc.update("DELETE FROM tb_controlled_claim_barrier");
        List<Process> processes=new ArrayList<>();List<Path> results=new ArrayList<>();
        try{
            for(String id:Arrays.asList("a","b")){
                Path file=Paths.get(System.getenv("CONTROLLED_TEST_OUT"),"claim-process-"+id+".json");results.add(file);
                ProcessBuilder command=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-Duser.timezone=UTC","-cp",System.getenv("CONTROLLED_CHILD_CP"),DispatchClaimProcess.class.getName(),id,file.toString());
                command.redirectErrorStream(true).redirectOutput(file.resolveSibling("claim-process-"+id+".log").toFile());processes.add(command.start());
            }
            Set<String> ids=new HashSet<>();int total=0;
            for(int n=0;n<processes.size();n++){
                assertTrue(processes.get(n).waitFor(45,TimeUnit.SECONDS),"Child JVM finite deadline");assertEquals(0,processes.get(n).exitValue());
                for(Object id:new ObjectMapper().readValue(results.get(n).toFile(),List.class)){total++;assertTrue(ids.add(id.toString()));}
            }
            assertEquals(2,total);assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'",Integer.class));
        }finally{for(Process process:processes)if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
}
