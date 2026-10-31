package com.ticketing.integration;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.*;
import com.ticketing.risk.*;
import com.ticketing.sale.TicketSaleArmory;
import com.ticketing.ticket.*;
import com.ticketing.payment.*;
import com.ticketing.mq.TicketEventConsumer;
import com.ticketing.notification.OrderNotificationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses only an explicitly provisioned fresh test schema/Redis DB; never cleans developer data. */
@EnabledIfSystemProperty(named="ticketing.v2.integration",matches="true")
@SpringBootTest(classes=TicketPlatformApplication.class,properties={
 "spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always",
 "ticketing.reservations-v2.enabled=true","ticketing.sale.armory.enabled=true","ticketing.inventory.bucket-count=16",
 "ticketing.reservations-v2.poll-ms=3600000","ticketing.risk.summary.enabled=true",
 "ticketing.mq.enabled=false","ticketing.jobs.enabled=false","ticketing.metrics.snapshot.enabled=false",
 "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
 "ticketing.pools.sale.core=4","ticketing.pools.sale.max=4","ticketing.pools.sale.queue=0"})
class ReservationV2IT {
    static {org.springframework.core.SpringProperties.setProperty("spring.jdbc.getParameterType.ignore","true");}
    @Autowired Db db;@Autowired TicketSaleArmory armory;@Autowired StringRedisTemplate redis;
    @Autowired ReservationV2Service service;@Autowired ReservationDispatch queue;@Autowired ReservationV2Transactions transactions;
    @Autowired ReservationIntentService oldIntents;@Autowired RiskSubjectFence fences;
    @org.springframework.boot.test.mock.mockito.SpyBean(proxyTargetAware=true) InventoryService inventory;
    @Autowired PlatformTransactionManager manager;
    @Autowired WalletService wallet;@Autowired TicketEventConsumer consumer;
    @org.springframework.boot.test.mock.mockito.MockBean RiskAgentClient agent;
    @org.springframework.boot.test.mock.mockito.SpyBean(proxyTargetAware=true) OrderNotificationService notifications;
    @BeforeEach void resetAdvice(){
        reset(agent,notifications,inventory);
        when(agent.analyze(anyMap())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Map<String,Object> value=new HashMap<>();value.put("aiAvailable",true);value.put("riskLevel","HIGH");value.put("scoreAdjustment",20);
            value.put("confidence",1d);value.put("reasons",Collections.singletonList("test investigation evidence"));value.put("suggestion","BLOCK");return value;
        });
    }
    static AtomicLong users=new AtomicLong(50000);
    static class Sale {String event=Db.id(),session=Db.id(),sku=Db.id();long start;}
    Sale sale(String verdict)throws Exception{
        Sale s=new Sale();s.start=(System.currentTimeMillis()/1000+15)*1000;
        db.jdbc.update("INSERT INTO tb_event(id,name,venue,status) VALUES(?,'V2 fixture','test','ON_SALE')",s.event);
        db.jdbc.update("INSERT INTO tb_event_session VALUES(?,?,?)",s.session,s.event,new Timestamp(s.start+86400000));
        db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES(?,?,'fixture',10,100,100,?,?,2,'ON_SALE',0)",s.sku,s.session,new Timestamp(s.start),new Timestamp(s.start+3600000));
        String original=db.one("SELECT config FROM tb_risk_rule WHERE rule_code='DEVICE_ACCOUNTS'").get("config").toString();
        if("REVIEW".equals(verdict))db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DEVICE_ACCOUNTS'","{\"feature\":\"deviceAccounts\",\"threshold\":0,\"fast\":false}");
        try{armory.assemble(s.session,0,"assemble-"+s.session,1);}finally{db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DEVICE_ACCOUNTS'",original);}
        long wait=s.start-System.currentTimeMillis()+20;if(wait>0)Thread.sleep(wait);return s;
    }
    ReserveRequest request(Sale sale){
        long user=users.incrementAndGet();db.jdbc.update("INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES(?,?,'fixture','USER','2025-01-01')",user,"139"+String.format("%08d",user));
        db.jdbc.update("INSERT INTO tb_wallet_account(user_id,available_balance) VALUES(?,10000)",user);
        ReserveRequest r=new ReserveRequest();r.setRequestId(Db.id());r.setUserId(user);r.setEventSessionId(sale.session);r.setTicketSkuId(sale.sku);r.setQuantity(1);r.setDeviceId("device-"+user);return r;
    }
    Map<String,Object> enqueue(ReserveRequest r){return service.submit(r,"test-ip-"+r.getUserId());}
    Map<String,Object> settle(ReserveRequest r)throws Exception{
        long end=System.currentTimeMillis()+15000;Map<String,Object> job;
        do{service.dispatch();job=queue.byRequest(r.getRequestId());if(Arrays.asList("RESERVED","REJECTED","FAILED","EXPIRED").contains(job.get("state")))return job;Thread.sleep(25);}while(System.currentTimeMillis()<end);
        fail("V2 job did not settle: "+job);return job;
    }
    long stock(Sale s){return db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id=?",Long.class,s.sku);}
    long redisStock(Sale s){long n=0;for(int i=0;i<16;i++)n+=Long.parseLong(redis.opsForValue().get(InventoryService.prefix(s.sku)+"stock:bucket:"+i));return n;}
    @Test void passQueueDoesNotReserveAndConfirmedHoldHasExactlyTenMinutes()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);Map<String,Object> response=enqueue(r);
        assertEquals("QUEUED",response.get("state"));assertEquals(false,response.get("reservationSuccess"));assertFalse(response.containsKey("expiresAt"));
        assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        Map<String,Object> done=settle(r);assertEquals("RESERVED",done.get("state"));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));
        String marker=InventoryService.prefix(s.sku)+"reservation:"+done.get("reservation_id");
        long held=Long.parseLong(redis.opsForHash().get(marker,"heldAt").toString()),expires=Long.parseLong(redis.opsForHash().get(marker,"expiresAt").toString());
        assertEquals(600000,expires-held);
        assertEquals("WAITING_PAYMENT",db.one("SELECT status FROM tb_ticket_order WHERE id=?",done.get("order_id")).get("status"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE aggregate_id=? AND message_key LIKE 'AUTO_PAYMENT:%'",Integer.class,done.get("order_id")));
        Map<String,Object> replay=enqueue(r);assertEquals(done.get("reservation_id"),replay.get("reservationId"));assertEquals(99,stock(s));
    }
    @Test void blockIsDurablyAuditedBeforeAnyRedisOrMysqlInventoryMutation()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);String ip="test-ip-"+r.getUserId();
        new TransactionTemplate(manager).executeWithoutResult(t->{fences.exclusive(r.getUserId(),r.getDeviceId(),ip);db.jdbc.update("INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,expires_at) VALUES(?,'USER',?,'HIGH','BLOCK',100,?)",Db.id(),r.getUserId().toString(),new Timestamp(System.currentTimeMillis()+60000));});
        Map<String,Object> result=enqueue(r);assertEquals("REJECTED",result.get("state"));assertEquals(false,result.get("reservationSuccess"));
        assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE request_id=?",Integer.class,r.getRequestId()));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_risk_audit a JOIN tb_reservation_job j ON j.id=a.job_id WHERE j.request_id=? AND a.decision='BLOCK'",Integer.class,r.getRequestId()));
    }
    @Test void armoryRecoveryPreservesCommittedV2DeadlineAndBucketAccounting()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);enqueue(r);Map<String,Object> done=settle(r);
        assertEquals("RESERVED",done.get("state"));
        String marker=InventoryService.prefix(s.sku)+"reservation:"+done.get("reservation_id");
        String expires=redis.opsForHash().get(marker,"expiresAt").toString();
        String held=redis.opsForHash().get(marker,"heldAt").toString();
        redis.delete(marker);
        armory.recover(s.session,1,"recover-"+s.session,1);
        assertEquals("2",redis.opsForHash().get(marker,"protocolVersion"));
        assertEquals("2",redis.opsForHash().get(marker,"assemblyVersion"));
        assertEquals(expires,redis.opsForHash().get(marker,"expiresAt"));
        assertEquals(held,redis.opsForHash().get(marker,"heldAt"));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));
        assertEquals(done.get("reservation_id"),enqueue(r).get("reservationId"));
        wallet.pay(done.get("order_id").toString(),r.getUserId(),payRequest(r),"recover-ip");
        assertEquals("PAID",db.one("SELECT status FROM tb_ticket_order WHERE id=?",done.get("order_id")).get("status"));
    }
    @Test void recoveryCannotCrossLuaAndV2DatabaseCommitBoundary()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);enqueue(r);
        Map<String,Object> claimed=queue.claim();assertNotNull(claimed);transactions.prepare(claimed);
        java.util.concurrent.CountDownLatch luaHeld=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch commitAllowed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        doAnswer(call->{Object result=call.callRealMethod();luaHeld.countDown();
            assertTrue(commitAllowed.await(30,java.util.concurrent.TimeUnit.SECONDS));return result;
        }).when(inventory).reserveRuntime(any(ReservationIntent.class),anyLong(),anyLong());
        try{
            java.util.concurrent.Future<?> reserve=workers.submit(()->transactions.reserve(claimed));
            assertTrue(luaHeld.await(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(99,redisStock(s));assertEquals(100,stock(s));
            java.util.concurrent.Future<?> recovery=workers.submit(()->armory.recover(s.session,1,"racing-recover-"+s.session,1));
            assertThrows(java.util.concurrent.TimeoutException.class,()->recovery.get(300,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals("1",redis.opsForValue().get(TicketSaleArmory.prefix(s.session)+"active-version"));
            commitAllowed.countDown();reserve.get(30,java.util.concurrent.TimeUnit.SECONDS);recovery.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("RESERVED",queue.byRequest(r.getRequestId()).get("state"));
            assertEquals(99,stock(s));assertEquals(99,redisStock(s));
            assertEquals("2",redis.opsForValue().get(TicketSaleArmory.prefix(s.session)+"active-version"));
        }finally{commitAllowed.countDown();workers.shutdownNow();reset(inventory);}
    }
    @Test void reviewCanReserveWithoutAgent()throws Exception{
        Sale s=sale("REVIEW");ReserveRequest r=request(s);enqueue(r);assertEquals("REVIEW",queue.byRequest(r.getRequestId()).get("decision"));
        Map<String,Object> done=settle(r);assertEquals("RESERVED",done.get("state"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_decision WHERE order_id=? AND phase='ADMISSION' AND decision='REVIEW'",Integer.class,done.get("order_id")));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_decision WHERE order_id=? AND phase='POST_ISSUE'",Integer.class,done.get("order_id")));
        verifyNoInteractions(agent);
    }
    @Test void legacyLuaArgumentsStillReserveAndReleaseAnArmedBucketExactlyOnce()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);inventory.ensureReady(s.sku);
        ReservationIntent intent=oldIntents.create(r,"legacy-ip-"+r.getUserId());
        assertTrue(inventory.reserve(intent)>0);assertEquals(99,redisStock(s));assertEquals(100,stock(s));
        String marker=InventoryService.prefix(s.sku)+"reservation:"+intent.getReservationId();
        assertNull(redis.opsForHash().get(marker,"protocolVersion"));
        inventory.release(s.sku,r.getUserId(),intent.getReservationId());
        inventory.release(s.sku,r.getUserId(),intent.getReservationId());
        assertEquals(100,redisStock(s));assertEquals(100,stock(s));
        db.jdbc.update("UPDATE tb_reserve_request SET status='FAILED' WHERE request_id=? AND status='REQUESTED'",r.getRequestId());
    }
    @Test void manifestDeletedAfterQueueingCannotBeMaskedByParsedCache()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);assertEquals("QUEUED",enqueue(r).get("state"));redis.delete(TicketSaleArmory.prefix(s.session)+"manifest:v1");
        assertEquals("FAILED",settle(r).get("state"));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
    }
    @Test void versionChangedWhileQueuedRejectsOldAuthorization()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);assertEquals("QUEUED",enqueue(r).get("state"));armory.recover(s.session,1,"recover-"+s.session,1);
        assertEquals("FAILED",settle(r).get("state"));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
    }
    @Test void mysqlQuotaFailureAfterLuaIsRolledBackAndCompensatedExactlyOnce()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);enqueue(r);
        db.jdbc.update("INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(?,?,2)",r.getUserId(),s.sku);
        Map<String,Object> job=settle(r);assertEquals("FAILED",job.get("state"));assertEquals("PURCHASE_LIMIT",job.get("result_code"));
        assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation WHERE sku_id=?",Integer.class,s.sku));
        ReservationIntent intent=oldIntents.create(r,"test-ip-"+r.getUserId());
        inventory.abortRuntime(intent,1);assertEquals(100,redisStock(s));
        assertEquals(-5,inventory.reserveRuntime(intent,1,System.currentTimeMillis()+30000));
    }
    PaymentRequest payRequest(ReserveRequest r){PaymentRequest p=new PaymentRequest();p.setPaymentRequestId(Db.id());p.setDeviceId(r.getDeviceId());return p;}
    void consume(String type,String order){consumer.consume(type+":"+order,order,type);}
    @Test void paymentIssueNotificationRetryAndLateReservationEvidenceAreIndependent()throws Exception{
        Sale s=sale("PASS");ReserveRequest r=request(s);enqueue(r);Map<String,Object> done=settle(r);String order=done.get("order_id").toString();
        wallet.pay(order,r.getUserId(),payRequest(r),"new-ip");consume("ISSUE",order);consume("ISSUE",order);
        assertEquals("ISSUED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));
        consume("RESERVATION_CREATED",order);
        assertEquals("SUCCESS",db.one("SELECT status FROM tb_workflow WHERE workflow_id=?",order).get("status"));
        assertEquals("ISSUE",db.one("SELECT current_node FROM tb_workflow WHERE workflow_id=?",order).get("current_node"));
        doThrow(new IllegalStateException("test notification delivery failed")).doCallRealMethod().when(notifications).deliver(order);
        assertThrows(IllegalStateException.class,()->consume("NOTIFY",order));
        assertEquals("ISSUED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));
        assertEquals("PENDING",db.one("SELECT status FROM tb_order_notification WHERE order_id=?",order).get("status"));
        consume("NOTIFY",order);consume("NOTIFY",order);
        Map<String,Object> notice=notifications.list(r.getUserId(),true).get(0);notifications.read(notice.get("id").toString(),r.getUserId());
        assertEquals(1,db.jdbc.queryForObject("SELECT delivery_attempts FROM tb_order_notification WHERE order_id=?",Integer.class,order));
        assertTrue(notifications.list(r.getUserId(),true).isEmpty());verifyNoInteractions(agent);
    }
    @Test void reviewInvestigationBlocksFutureRequestButCannotRevokeAnotherCommittedQualification()throws Exception{
        Sale s=sale("REVIEW");ReserveRequest first=request(s);enqueue(first);String a=settle(first).get("order_id").toString();
        wallet.pay(a,first.getUserId(),payRequest(first),"ip");consume("ISSUE",a);
        ReserveRequest second=new ReserveRequest();second.setRequestId(Db.id());second.setUserId(first.getUserId());second.setEventSessionId(s.session);second.setTicketSkuId(s.sku);second.setQuantity(1);second.setDeviceId(first.getDeviceId());
        enqueue(second);String b=settle(second).get("order_id").toString();verifyNoInteractions(agent);
        consume("RISK_CHECK",a);consume("RISK_CHECK",a);verify(agent,times(1)).analyze(anyMap());
        assertEquals("SUCCESS",db.one("SELECT status FROM tb_workflow WHERE workflow_id=?",a).get("status"));
        assertEquals("ISSUE",db.one("SELECT current_node FROM tb_workflow WHERE workflow_id=?",a).get("current_node"));
        assertEquals("BLOCK",db.one("SELECT decision FROM tb_risk_profile WHERE subject_type='USER' AND subject_value=?",first.getUserId().toString()).get("decision"));
        assertEquals("SUCCESS",wallet.pay(b,second.getUserId(),payRequest(second),"ip").get("status"));consume("ISSUE",b);
        assertEquals("ISSUED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",b).get("status"));
        ReserveRequest third=new ReserveRequest();third.setRequestId(Db.id());third.setUserId(first.getUserId());third.setEventSessionId(s.session);third.setTicketSkuId(s.sku);third.setQuantity(1);third.setDeviceId(first.getDeviceId());
        assertEquals("REJECTED",enqueue(third).get("state"));assertEquals("PROFILE_BLOCK",queue.byRequest(third.getRequestId()).get("result_code"));assertEquals(98,stock(s));
    }
}
