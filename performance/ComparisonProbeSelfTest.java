package fullchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import com.ticketing.ticket.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Offline H2 instrumentation assertions; no HTTP, Redis, inventory or orders. */
public class ComparisonProbeSelfTest {
    public static class FakeDriver {public long getId(){return 777;}}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Connection fake=(Connection)Proxy.newProxyInstance(ComparisonProbeSelfTest.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->m.getName().equals("unwrap")?new FakeDriver():null);
        FullChainProbe.connected(fake);require(Long.valueOf(777).equals(FullChainProbe.CONNECTION.get()),"Driver getter adaptation");require(FullChainProbe.CONNECTION_MISSES.get()==0,"No getter failures");
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:batchprobe;MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
        // Keep the H2 schema alive across the test's separate JDBC calls.
        ds.setUrl("jdbc:h2:mem:batchprobe;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Db db=new Db(new JdbcTemplate(ds),new ObjectMapper());new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql")).execute(ds);
        ReservationDispatch target=new ReservationDispatch(db,new RiskSubjectFence(db,new StringRedisTemplate()),new DataSourceTransactionManager(ds));
        ReflectionTestUtils.setField(target,"capacity",512);ReflectionTestUtils.setField(target,"workers",4);ReflectionTestUtils.setField(target,"passWeight",2);ReflectionTestUtils.setField(target,"reviewAge",2000L);ReflectionTestUtils.setField(target,"queueTimeout",30000L);target.initialize();
        for(int i=0;i<4;i++)db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,queue_expires_at,reservation_id,order_id) VALUES(?,?,'f',?,'s','sku',1,'d','ip',1,'QUEUED','PASS',?,?,?)","job"+i,"req"+i,i,new Timestamp(System.currentTimeMillis()+30000),"res"+i,"ord"+i);
        AspectJProxyFactory factory=new AspectJProxyFactory(target);factory.addAspect(new FullChainProbe.ProbeAspect());ReservationDispatch proxy=factory.getProxy();
        FullChainProbe.Trace caller=new FullChainProbe.Trace("outer");FullChainProbe.CURRENT.set(caller);FullChainProbe.PHASE.get().push("outer");
        List<Map<String,Object>> jobs=proxy.claimBatch(4);require(jobs.size()==4,"One batch of four");require(FullChainProbe.CURRENT.get()==caller,"Trace context restored");
        Map<String,Object> batch=FullChainProbe.CLAIMS.get(0);require(((Number)batch.get("size")).intValue()==4,"Batch cardinality");require(!((List<?>)batch.get("spans")).isEmpty(),"Shared SQL recorded once");
        for(Map<String,Object> job:jobs){FullChainProbe.Trace t=FullChainProbe.ALL.get(job.get("request_id"));require(t.claimCount==1&&t.claimBatchId.equals(batch.get("batchId")),"Task links unique batch");require(t.spans.isEmpty(),"Shared SQL must not be copied to each task");FullChainProbe.saleSubmit("sale");FullChainProbe.saleSubmitted(null);require(t.saleSubmit>0&&t.saleSubmitSuccess,"Ordered task submit correlation");FullChainProbe.processStart(job);require(t.processLease.equals(t.claimLease),"Lease correlation");FullChainProbe.processEnd();}
        require(FullChainProbe.CLAIMED_QUEUE.get().isEmpty(),"No stale submission associations");
        ReservationV2Service service=new ReservationV2Service(proxy,null,null,null,null,null,new SimpleMeterRegistry());Method dispatch=ReservationV2Service.class.getDeclaredMethod("dispatchOnce");dispatch.setAccessible(true);dispatch.invoke(service);
        require(FullChainProbe.SCHEDULED.stream().anyMatch(x->"ReservationV2Service.dispatchOnce".equals(x.get("operation"))),"Self invocation dispatch hook");
        require(FullChainProbe.CLAIMS.stream().anyMatch(x->((Number)x.get("size")).intValue()==0),"Empty attempts retained");
        System.out.println("PROBE_SELF_TEST_OK: batch SQL once, four task links, submit/lease, empty attempt, direct dispatch hook, getter");
    }
}
