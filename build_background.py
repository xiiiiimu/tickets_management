from build_ticketing import java,put
java('infrastructure/TaskPools','''
package com.ticketing.infrastructure;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import io.micrometer.core.instrument.*;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PreDestroy;
@Component
public class TaskPools {
 private final Map<String,ThreadPoolTaskExecutor> pools=new LinkedHashMap<>();
 public TaskPools(Environment env,MeterRegistry registry){for(String name:Arrays.asList("outbox","compensation","reservation","timeout","cache","risk")){
  ThreadPoolTaskExecutor p=new ThreadPoolTaskExecutor();String prefix="ticketing.pools."+name+".";
  p.setCorePoolSize(env.getProperty(prefix+"core",Integer.class,2));p.setMaxPoolSize(env.getProperty(prefix+"max",Integer.class,4));p.setQueueCapacity(env.getProperty(prefix+"queue",Integer.class,100));p.setThreadNamePrefix(name+"-");
  AtomicLong rejects=new AtomicLong();p.setRejectedExecutionHandler((r,e)->{rejects.incrementAndGet();throw new RejectedExecutionException(name);});
  p.setTaskDecorator(r->{Map<String,String> context=MDC.getCopyOfContextMap();return ()->{if(context!=null)MDC.setContextMap(context);try{r.run();}finally{MDC.clear();}};});p.setWaitForTasksToCompleteOnShutdown(true);p.setAwaitTerminationSeconds(15);p.initialize();pools.put(name,p);
  Gauge.builder("threadpool.active",p,ThreadPoolTaskExecutor::getActiveCount).tag("pool",name).register(registry);
  Gauge.builder("threadpool.queue",p,x->x.getThreadPoolExecutor().getQueue().size()).tag("pool",name).register(registry);
  Gauge.builder("threadpool.core",p,ThreadPoolTaskExecutor::getCorePoolSize).tag("pool",name).register(registry);
  Gauge.builder("threadpool.max",p,ThreadPoolTaskExecutor::getMaxPoolSize).tag("pool",name).register(registry);
  Gauge.builder("threadpool.completed",p,x->x.getThreadPoolExecutor().getCompletedTaskCount()).tag("pool",name).register(registry);
  Gauge.builder("threadpool.reject",rejects,AtomicLong::get).tag("pool",name).register(registry);
 }}
 public void execute(String pool,Runnable task){pools.get(pool).execute(task);}
 public void resize(String name,int core,int max){if(!pools.containsKey(name)||core<1||max<core||max>128)throw new IllegalArgumentException();ThreadPoolTaskExecutor p=pools.get(name);if(max>p.getMaxPoolSize()){p.setMaxPoolSize(max);p.setCorePoolSize(core);}else{p.setCorePoolSize(core);p.setMaxPoolSize(max);}}
 @PreDestroy public void close(){pools.values().forEach(ThreadPoolTaskExecutor::shutdown);}
}
''')
java('outbox/OutboxPublisher','''
package com.ticketing.outbox;
import com.ticketing.infrastructure.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.sql.Timestamp;
@Component
public class OutboxPublisher {
 private final Db db;private final ObjectProvider<RocketMQTemplate> mq;private final int maxRetries;
 public OutboxPublisher(Db db,ObjectProvider<RocketMQTemplate> mq,@Value("${ticketing.outbox.max-retries:12}")int maxRetries){this.db=db;this.mq=mq;this.maxRetries=maxRetries;}
 public void publish(){
  List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT * FROM tb_outbox_message WHERE status IN ('PENDING','RETRY','SENDING') AND next_retry_time<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 50");
  for(Map<String,Object> row:rows){String token=Db.id();
   int claimed=db.jdbc.update("UPDATE tb_outbox_message SET status='SENDING',lease_token=?,next_retry_time=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RETRY','SENDING') AND next_retry_time<=CURRENT_TIMESTAMP",token,new Timestamp(System.currentTimeMillis()+60000),row.get("id"));if(claimed!=1)continue;
   try{RocketMQTemplate transport=mq.getIfAvailable();if(transport==null)throw new IllegalStateException("MQ unavailable");
    transport.syncSend(row.get("topic").toString(),MessageBuilder.withPayload(row.get("payload").toString()).setHeader("KEYS",row.get("message_key")).build(),3000);
    db.jdbc.update("UPDATE tb_outbox_message SET status='SENT',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND status='SENDING'",row.get("id"),token);
   }catch(Exception e){int attempts=((Number)row.get("retry_count")).intValue()+1;long delay=Math.min(300000,1000L*(1L<<Math.min(attempts,8)));
    db.jdbc.update("UPDATE tb_outbox_message SET status=?,retry_count=?,next_retry_time=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND status='SENDING'",attempts>=maxRetries?"FAILED":"RETRY",attempts,new Timestamp(System.currentTimeMillis()+delay),row.get("id"),token);
   }
  }
 }
 public void compensate(){db.jdbc.update("UPDATE tb_outbox_message SET status='RETRY',retry_count=0,next_retry_time=CURRENT_TIMESTAMP WHERE status='FAILED' AND updated_at<?",new Timestamp(System.currentTimeMillis()-3600000));}
}
''')
java('mq/RocketTicketListener','''
package com.ticketing.mq;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
@Component @ConditionalOnProperty(name="ticketing.mq.enabled",havingValue="true",matchIfMissing=true)
@RocketMQMessageListener(topic="ticket-events",consumerGroup="ticket-events",consumeThreadMax=16)
public class RocketTicketListener implements RocketMQListener<String> {
 private final TicketEventConsumer consumer;private final ObjectMapper json;
 public RocketTicketListener(TicketEventConsumer consumer,ObjectMapper json){this.consumer=consumer;this.json=json;}
 public void onMessage(String payload){try{Map<?,?> m=json.readValue(payload,Map.class);consumer.consume(m.get("messageKey").toString(),m.get("orderId").toString(),m.get("type").toString());}catch(Exception e){throw new IllegalStateException("Ticket event must retry",e);}}
}
''')
java('inventory/ReservationRecoveryService','''
package com.ticketing.inventory;
import com.ticketing.infrastructure.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service
public class ReservationRecoveryService {
 private final Db db;private final InventoryService inventory;
 public ReservationRecoveryService(Db db,InventoryService inventory){this.db=db;this.inventory=inventory;}
 @Transactional public void recover(String request){
  Map<String,Object> r=db.one("SELECT * FROM tb_reserve_request WHERE request_id=? FOR UPDATE",request);
  if(r!=null&&"REQUESTED".equals(r.get("status"))&&((java.sql.Timestamp)r.get("created_at")).getTime()<System.currentTimeMillis()-120000){
   inventory.release(r.get("sku_id").toString(),r.get("user_id"),r.get("reservation_id").toString());
   db.jdbc.update("UPDATE tb_reserve_request SET status='FAILED' WHERE request_id=? AND status='REQUESTED'",request);
  }
 }
}
''')
java('infrastructure/BackgroundJobs','''
package com.ticketing.infrastructure;
import com.ticketing.inventory.*;
import com.ticketing.outbox.OutboxPublisher;
import com.ticketing.order.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
@Component @ConditionalOnProperty(name="ticketing.jobs.enabled",havingValue="true",matchIfMissing=true)
public class BackgroundJobs {
 private final Db db;private final TaskPools pools;private final OutboxPublisher outbox;private final ReservationRecoveryService recovery;private final OrderService orders;private final InventoryService inventory;
 private final Map<String,AtomicBoolean> running=new ConcurrentHashMap<>();
 public BackgroundJobs(Db db,TaskPools pools,OutboxPublisher outbox,ReservationRecoveryService recovery,OrderService orders,InventoryService inventory){this.db=db;this.pools=pools;this.outbox=outbox;this.recovery=recovery;this.orders=orders;this.inventory=inventory;}
 private void submit(String name,Runnable work){AtomicBoolean flag=running.computeIfAbsent(name,k->new AtomicBoolean());if(!flag.compareAndSet(false,true))return;try{pools.execute(name,()->{try{work.run();}catch(Exception e){LoggerFactory.getLogger(getClass()).warn("Background task {} will retry",name,e);}finally{flag.set(false);}});}catch(Exception e){flag.set(false);}}
 @Scheduled(fixedDelayString="${ticketing.jobs.interval-ms:1000}") public void outbox(){submit("outbox",outbox::publish);}
 @Scheduled(fixedDelay=30000) public void compensation(){submit("compensation",outbox::compensate);}
 @Scheduled(fixedDelay=10000) public void recover(){submit("reservation",()->{for(Map<String,Object> r:db.jdbc.queryForList("SELECT request_id FROM tb_reserve_request WHERE status='REQUESTED' AND created_at<? LIMIT 100",new java.sql.Timestamp(System.currentTimeMillis()-120000)))recovery.recover(r.get("request_id").toString());});}
 @Scheduled(fixedDelay=5000) public void expire(){submit("timeout",()->{for(Map<String,Object> r:db.jdbc.queryForList("SELECT id FROM tb_ticket_order WHERE status IN ('CREATED','RISK_CHECKING','RISK_REVIEW','WAITING_PAYMENT') AND expires_at<CURRENT_TIMESTAMP LIMIT 100"))orders.expire(r.get("id").toString());});}
 @Scheduled(fixedDelay=30000) public void cache(){submit("cache",()->{for(Map<String,Object> sku:db.jdbc.queryForList("SELECT * FROM tb_ticket_sku"))inventory.configure(sku);});}
}
''')
java('infrastructure/PlatformMetrics','''
package com.ticketing.infrastructure;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.*;
@Component
public class PlatformMetrics {
 public PlatformMetrics(Db db,MeterRegistry registry){
  for(String name:new String[]{"ticket.reserve.success","ticket.reserve.failed","order.created","order.cancelled","risk.pass","risk.review","risk.block","payment.success","payment.failed","mq.consume.duplicate"})registry.counter(name);
  Gauge.builder("mq.outbox.pending",db,d->d.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE status<>'SENT'",Double.class)).register(registry);
  Gauge.builder("ticket.stock.remaining",db,d->d.jdbc.queryForObject("SELECT COALESCE(SUM(remaining_stock),0) FROM tb_ticket_sku",Double.class)).register(registry);
 }
}
''')
put('ticket-risk-platform/src/main/resources/application.yml','''
server:
  port: 8082
spring:
  application:
    name: ticket-risk-platform
  datasource:
    driver-class-name: com.mysql.jdbc.Driver
    url: ${DB_URL:jdbc:mysql://127.0.0.1:3307/ticketing?useSSL=false&serverTimezone=UTC&characterEncoding=utf8}
    username: ${DB_USER:ticketing}
    password: ${DB_PASSWORD:ticketing-local}
    initialization-mode: never
  redis:
    host: ${REDIS_HOST:127.0.0.1}
    port: ${REDIS_PORT:6379}
    timeout: 2s
  jackson:
    default-property-inclusion: non_null
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
rocketmq:
  name-server: ${MQ_NAMESERVER:127.0.0.1:9876}
  producer:
    group: ticket-outbox-producer
    send-message-timeout: 3000
ticketing:
  dev:
    expose-login-code: ${DEMO_LOGIN_ENABLED:false}
  mq:
    enabled: true
  agent:
    url: ${RISK_AGENT_URL:http://127.0.0.1:8090}
    key: ${INTERNAL_API_KEY:}
  outbox:
    max-retries: 12
  jobs:
    enabled: true
    interval-ms: 1000
  pools:
    outbox: {core: 2, max: 4, queue: 100}
    compensation: {core: 1, max: 2, queue: 50}
    reservation: {core: 1, max: 2, queue: 50}
    timeout: {core: 1, max: 2, queue: 50}
    cache: {core: 1, max: 2, queue: 50}
    risk: {core: 4, max: 16, queue: 200}
logging:
  pattern:
    level: '%5p [trace=%X{traceId}]'
''')
