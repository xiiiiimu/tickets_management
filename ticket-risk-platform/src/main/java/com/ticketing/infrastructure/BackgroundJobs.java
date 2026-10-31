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
 @Scheduled(fixedDelay=5000) public void expire(){submit("timeout",()->{for(Map<String,Object> r:db.jdbc.queryForList("SELECT id FROM tb_ticket_order WHERE status IN ('CREATED','WAITING_PAYMENT','RISK_CHECKING','RISK_REVIEW') AND expires_at<CURRENT_TIMESTAMP LIMIT 100"))orders.expire(r.get("id").toString());});}
 @Scheduled(fixedDelay=30000) public void cache(){submit("cache",()->{for(Map<String,Object> sku:db.jdbc.queryForList("SELECT id FROM tb_ticket_sku"))inventory.configure(sku.get("id").toString());});}
}
