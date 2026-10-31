package benchmark;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.inventory.InventoryService;
import com.ticketing.ticket.ReservationIntent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Owned test JVM only. Fault barrier is after real Lua and before its MySQL turn commit. */
public class VectorWorker {
    static class ReadyScheduler extends com.ticketing.ticket.ReservationDispatchScheduler {
        private Runnable task;
        ReadyScheduler(org.springframework.core.env.Environment env,io.micrometer.core.instrument.MeterRegistry metrics){super(env,metrics);}
        @Override public synchronized void start(Runnable task){this.task=task;}
        synchronized void release(){super.start(task);}
    }
    @Configuration @Import(TicketPlatformApplication.class) static class WorkerConfiguration {
        @Bean @Primary @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="vector.preflight",havingValue="true",matchIfMissing=true)
        ReadyScheduler readyScheduler(org.springframework.core.env.Environment env,io.micrometer.core.instrument.MeterRegistry metrics){return new ReadyScheduler(env,metrics);}
        @Bean static BeanPostProcessor pauseAfterLua(){return new BeanPostProcessor(){
            public Object postProcessAfterInitialization(Object bean,String name){
                if(bean instanceof com.ticketing.ticket.ReservationV2Transactions){
                    ProxyFactory diagnostics=new ProxyFactory(bean);diagnostics.setProxyTargetClass(true);
                    diagnostics.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{try{return call.proceed();}catch(Throwable error){System.err.println("VECTOR_STAGE_FAILURE "+call.getMethod().getName());error.printStackTrace();System.err.flush();throw error;}});return diagnostics.getProxy();
                }
                if(!(bean instanceof InventoryService)||!Boolean.getBoolean("vector.pause"))return bean;
                ProxyFactory proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
                proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{Object result=call.proceed();
                    if("reserveRuntime".equals(call.getMethod().getName())&&Long.valueOf(1).equals(result)){
                        System.out.println("VECTOR_PAUSED "+((ReservationIntent)call.getArguments()[0]).getRequestId());System.out.flush();
                        new CountDownLatch(1).await(20,TimeUnit.SECONDS);throw new IllegalStateException("Fault barrier expired");
                    }return result;});return proxy.getProxy();
            }
        };}
    }
    public static void main(String[] args)throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"))throw new IllegalStateException("Owned test URL required");
        try(Connection c=DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT owner FROM tb_controlled_owner")){if(!r.next()||!r.getString(1).equals(System.getenv("CONTROLLED_TEST_OWNER")))throw new IllegalStateException("Owner mismatch");}
        Timer watchdog=new Timer(true);watchdog.schedule(new TimerTask(){public void run(){Runtime.getRuntime().halt(9);}},90000);
        Map<String,Object> p=new HashMap<>();p.put("spring.datasource.driver-class-name","com.mysql.jdbc.Driver");p.put("spring.datasource.initialization-mode","never");
        p.put("ticketing.reservations-v2.enabled",true);p.put("ticketing.reservations-v2.attempts.enabled",true);p.put("ticketing.reservations-v2.attempts.ordered-lua",true);p.put("ticketing.reservations-v2.attempts.quick-review",true);p.put("ticketing.inventory.vector-reservations",true);p.put("ticketing.reservations-v2.dispatch.isolated",true);p.put("ticketing.reservations-v2.dispatch.wakeup",true);p.put("ticketing.reservations-v2.poll-ms",25);p.put("ticketing.reservations-v2.accepting",false);
        p.put("ticketing.sale.armory.enabled",true);p.put("ticketing.inventory.bucket-count",16);p.put("ticketing.pools.sale.core",4);p.put("ticketing.pools.sale.max",4);p.put("ticketing.pools.sale.queue",0);p.put("ticketing.mq.enabled",false);p.put("ticketing.jobs.enabled",false);p.put("ticketing.metrics.snapshot.enabled",false);p.put("spring.autoconfigure.exclude","org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration");p.put("logging.level.root","WARN");
        p.put("ticketing.agent.key","");p.put("ticketing.agent.url","http://127.0.0.1:1");
        p.forEach((key,value)->System.setProperty(key,value.toString()));
        SpringApplication app=new SpringApplication(WorkerConfiguration.class);app.setWebApplicationType(WebApplicationType.NONE);app.setDefaultProperties(p);org.springframework.context.ConfigurableApplicationContext context=app.run();
        ReadyScheduler ready=context.getBeanProvider(ReadyScheduler.class).getIfAvailable();
        if(ready!=null){
            context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).execute((org.springframework.data.redis.core.RedisCallback<String>)connection->connection.ping());
            com.ticketing.infrastructure.Db db=context.getBean(com.ticketing.infrastructure.Db.class);db.jdbc.queryForObject("SELECT 1",Integer.class);
            org.springframework.transaction.support.TransactionTemplate warm=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            for(Map<String,Object> job:db.jdbc.queryForList("SELECT DISTINCT session_id,sku_id,sale_version FROM tb_reservation_job WHERE state='PROCESSING'"))warm.executeWithoutResult(status->context.getBean(com.ticketing.sale.TicketSaleArmory.class).runtimeSku(job.get("session_id").toString(),job.get("sku_id").toString(),((Number)job.get("sale_version")).longValue()));
        }
        System.out.println("VECTOR_WORKER_READY");System.out.flush();if(ready!=null)ready.release();
    }
}
