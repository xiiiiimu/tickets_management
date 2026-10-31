package com.ticketing.integration;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.*;
import com.ticketing.mq.TicketEventConsumer;
import com.ticketing.payment.*;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.risk.*;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import com.ticketing.sale.*;
import com.ticketing.ticket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(classes=TicketPlatformApplication.class,properties={
 "spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always",
 "ticketing.sale.armory.enabled=true","ticketing.inventory.bucket-count=16",
 "ticketing.mq.enabled=false","ticketing.jobs.enabled=false","ticketing.metrics.snapshot.enabled=false",
 "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
class TicketSaleArmoryIT {
 static { org.springframework.core.SpringProperties.setProperty("spring.jdbc.getParameterType.ignore","true"); }
 @Container static MySQLContainer<?> mysql=new MySQLContainer<>(System.getProperty("ticketing.test.mysql-image","mysql:8.0"))
  .withUrlParam("useSSL","false").withUrlParam("serverTimezone","UTC").withUrlParam("useLegacyDatetimeCode","false").withDatabaseName("armory");
 @Container static GenericContainer<?> cache=new GenericContainer<>(System.getProperty("ticketing.test.redis-image","redis:7-alpine")).withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
  registry.add("spring.datasource.url",mysql::getJdbcUrl);registry.add("spring.datasource.username",mysql::getUsername);
  registry.add("spring.datasource.password",mysql::getPassword);registry.add("spring.redis.host",cache::getHost);
  registry.add("spring.redis.port",()->cache.getMappedPort(6379));
 }
 @Autowired Db db; @Autowired TicketSaleArmory armory; @Autowired StringRedisTemplate redis;
 @Autowired InventoryService inventory; @Autowired StockBucketService buckets; @Autowired TicketInventoryRepository skus;
 @Autowired RiskDecisionService risk; @Autowired RiskRuleEvaluatorRegistry registry; @Autowired ObjectMapper json;
 @Autowired MeterRegistry metrics; @Autowired PlatformTransactionManager manager;
 @Autowired ReservationIntentService intents; @Autowired ReservationTransactionService reservations;
 @Autowired ReservationRecoveryService recovery; @Autowired TicketEventConsumer consumer; @Autowired WalletService wallet;

 static class Sale { String event=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();
  String sku=UUID.randomUUID().toString(),vip=UUID.randomUUID().toString();long start; }
 Sale sale() {
  Sale sale=new Sale();sale.start=(System.currentTimeMillis()/1000+6)*1000;
  db.jdbc.update("INSERT INTO tb_event(id,name,venue,status) VALUES(?,'Armory test','Test venue','ON_SALE')",sale.event);
  db.jdbc.update("INSERT INTO tb_event_session(id,event_id,starts_at) VALUES(?,?,?)",sale.session,sale.event,new Timestamp(sale.start+86400000));
  for(String id:Arrays.asList(sale.sku,sale.vip)){
   int stock=id.equals(sale.sku)?33:16;
   db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,purchase_limit,sale_start_time,sale_end_time,status) VALUES(?,?,'Ticket',10,?,?,2,?,?,'ON_SALE')",
    id,sale.session,stock,stock,new Timestamp(sale.start),new Timestamp(sale.start+3600000));
  }
  return sale;
 }
 void open(Sale sale)throws Exception {long wait=sale.start-System.currentTimeMillis()+50;if(wait>0)Thread.sleep(wait);}
 ReserveRequest request(Sale sale,String request){
  ReserveRequest r=new ReserveRequest();r.setRequestId(request);r.setUserId(2L);r.setEventSessionId(sale.session);
  r.setTicketSkuId(sale.sku);r.setQuantity(1);r.setDeviceId("device-"+sale.session);return r;
 }
 Map<String,Object> admission(Sale sale){
  SaleManifest plan=armory.admissionPlan(sale.session,sale.sku);
  Map<String,Object> result=new LinkedHashMap<>();result.put("decision","PASS");result.put("saleVersion",plan.version());return result;
 }
 Map<String,Object> reserve(Sale sale,String id){
  Map<String,Object> entry=admission(sale);ReserveRequest r=request(sale,id);
  intents.create(r,"127.0.0.1");return reservations.reserve(id,sale.session,entry);
 }
 long redisStock(String sku){long sum=0;for(int b=0;b<16;b++)sum+=Long.parseLong(redis.opsForValue().get(InventoryService.prefix(sku)+"stock:bucket:"+b));return sum;}
 long sqlStock(String sku){return db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id=?",Long.class,sku);}
 void deleteKeys(String prefix){
  List<String> keys=redis.execute((RedisCallback<List<String>>)connection->{
   List<String> found=new ArrayList<>();try(Cursor<byte[]> cursor=connection.scan(ScanOptions.scanOptions().match(prefix+"*").count(100).build())){
    while(cursor.hasNext())found.add(new String(cursor.next(),java.nio.charset.StandardCharsets.UTF_8));
   }return found;
  });
  if(keys!=null && !keys.isEmpty())redis.delete(keys);
 }
 TicketSaleArmory node(StringRedisTemplate redisClient){
  StockBucketService localBuckets=new StockBucketService(db,16);
  InventoryService localInventory=new InventoryService(redisClient,skus,new com.ticketing.repository.jdbc.JdbcReservationIntentRepository(db),metrics,manager);
  org.springframework.test.util.ReflectionTestUtils.setField(localInventory,"buckets",localBuckets);
  return new TicketSaleArmory(db,redisClient,skus,localBuckets,localInventory,risk,registry,json,metrics,manager);
 }

 @Test void readyIsNotOnSaleAndDuplicateAssemblyDoesNotAddStock(){
  Sale sale=sale();Map<String,Object> first=armory.assemble(sale.session,0,"first",1);
  assertEquals("READY",first.get("state"));assertEquals(1,((Number)first.get("active_version")).longValue());
  assertEquals(33,redisStock(sale.sku));assertEquals(33,sqlStock(sale.sku));assertEquals(16,redisStock(sale.vip));
  assertTrue(Boolean.TRUE.equals(armory.assemble(sale.session,0,"first",1).get("replayed")));
  assertEquals(33,redisStock(sale.sku));
  assertThrows(BusinessException.class,()->intents.create(request(sale,"early"),"127.0.0.1"));
  assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE request_id='early'",Integer.class));
  assertEquals(32,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_stock_bucket WHERE sku_id IN (?,?)",Integer.class,sale.sku,sale.vip));
 }
 @Test void twoIndependentInstancesCannotPublishTheSameGeneration()throws Exception{
  Sale sale=sale();TicketSaleArmory other=node(redis);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{
   List<Future<Boolean>> results=new ArrayList<>();
   for(int i=0;i<2;i++){TicketSaleArmory worker=i==0?armory:other;String op="race-"+i;
    results.add(pool.submit(()->{start.await();try{worker.assemble(sale.session,0,op,1);return true;}catch(BusinessException conflict){return false;}}));}
   start.countDown();int successes=0;for(Future<Boolean> result:results)if(result.get())successes++;
   assertEquals(1,successes);
  }finally{pool.shutdownNow();}
  assertEquals(1,((Number)armory.status(sale.session).get("version")).longValue());assertEquals(33,redisStock(sale.sku));
 }
 @Test void redisPublicationFailureLeavesFailedBarrierAndRetryIsIdempotent(){
  Sale sale=sale();StringRedisTemplate broken=spy(redis);
  doThrow(new IllegalStateException("injected publication failure")).when(broken).execute(
   argThat((org.springframework.data.redis.core.script.RedisScript<Long> script)->script.getScriptAsString().contains("SALE_MANIFEST_PUBLISH")),anyList(),any(),any());
  assertThrows(IllegalStateException.class,()->node(broken).assemble(sale.session,0,"broken",1));
  assertEquals("FAILED",armory.status(sale.session).get("state"));
  assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_stock_bucket WHERE sku_id=?",Integer.class,sale.sku));
  armory.assemble(sale.session,1,"retry",1);
  assertEquals("READY",armory.status(sale.session).get("state"));assertEquals(33,redisStock(sale.sku));assertEquals(33,sqlStock(sale.sku));
 }
 @Test void differentSessionsAndSkuKeysAreIsolated()throws Exception{
  Sale a=sale(),b=sale();armory.assemble(a.session,0,"a",1);armory.assemble(b.session,0,"b",1);open(b);
  redis.opsForValue().set(TicketSaleArmory.prefix(a.session)+"active-version","999");
  assertThrows(BusinessException.class,()->armory.admissionPlan(a.session,a.sku));
  assertNotNull(armory.admissionPlan(b.session,b.sku));
  assertThrows(BusinessException.class,()->armory.admissionPlan(b.session,a.sku));assertEquals(33,redisStock(a.sku));
 }
 @Test void switchedVersionRejectsOldAdmissionBeforeLua()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);open(sale);Map<String,Object> old=admission(sale);
  armory.assemble(sale.session,1,"v2",1);intents.create(request(sale,"old-version"),"127.0.0.1");
  assertThrows(BusinessException.class,()->reservations.reserve("old-version",sale.session,old));
  assertEquals(33,redisStock(sale.sku));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,sale.sku));
 }
 @Test void pendingIntentBlocksRecoveryWithoutTouchingStockThenCanBeReconciled()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);open(sale);intents.create(request(sale,"pending"),"127.0.0.1");
  assertThrows(BusinessException.class,()->armory.recover(sale.session,1,"unsafe",1));
  assertEquals("FAILED",armory.status(sale.session).get("state"));assertEquals(33,redisStock(sale.sku));
  db.jdbc.update("UPDATE tb_reserve_request SET created_at=? WHERE request_id='pending'",new Timestamp(System.currentTimeMillis()-180000));
  recovery.recover("pending");armory.recover(sale.session,2,"reconciled",1);
  assertEquals("READY",armory.status(sale.session).get("state"));assertEquals(33,redisStock(sale.sku));
 }
 @Test void restoreLostCachePreservesSoldBucketsLimitMarkersReplayAndRelease()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);open(sale);
  Map<String,Object> result=reserve(sale,"sold");String order=result.get("orderId").toString();String reservation=result.get("reservationId").toString();
  consumer.consume("AUTO_PAYMENT:"+order,order,"AUTO_PAYMENT");consumer.consume("ISSUE:"+order,order,"ISSUE");
  assertEquals(32,sqlStock(sale.sku));assertEquals(32,redisStock(sale.sku));
  String allocation=db.one("SELECT bucket_id FROM tb_stock_bucket_allocation WHERE reservation_id=?",reservation).get("bucket_id").toString();
  deleteKeys(InventoryService.prefix(sale.sku));deleteKeys(InventoryService.prefix(sale.vip));deleteKeys(TicketSaleArmory.prefix(sale.session));
  assertThrows(BusinessException.class,()->inventory.ensureReady(sale.sku));
  assertThrows(BusinessException.class,()->armory.admissionPlan(sale.session,sale.sku));
  armory.recover(sale.session,1,"restore",1);
  assertEquals(32,redisStock(sale.sku));assertEquals(32,sqlStock(sale.sku));
  assertEquals("1",redis.opsForValue().get(InventoryService.prefix(sale.sku)+"user:2"));
  assertEquals(allocation,redis.opsForHash().get(InventoryService.prefix(sale.sku)+"reservation:"+reservation,"bucketId"));
  assertEquals("ISSUED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));
  assertEquals(order,reservations.reserve("sold",sale.session).get("orderId"));assertEquals(32,redisStock(sale.sku));
  PaymentRequest refund=new PaymentRequest();refund.setPaymentRequestId("refund-"+order);refund.setDeviceId("device-"+sale.session);
  wallet.refund(order,2,refund);consumer.consume("RELEASE:"+order,order,"RELEASE");consumer.consume("RELEASE:"+order,order,"RELEASE");
  assertEquals(33,redisStock(sale.sku));assertEquals(33,sqlStock(sale.sku));
 }
 @Test void frozenRulesAreUsedAndRecoveryDoesNotPublishGlobalEdits()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);open(sale);
  String original=db.one("SELECT config FROM tb_risk_rule WHERE rule_code='DECISION_POLICY'").get("config").toString();
  try{
   Map<String,Object> changed=json.readValue(original,Map.class);changed.put("review",70);
   db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DECISION_POLICY'",json.writeValueAsString(changed));
   assertEquals(50,armory.admissionPlan(sale.session,sale.sku).rules().stream().filter(r->r.getRuleCode().equals("DECISION_POLICY")).findFirst().get().getConfig().get("review"));
   armory.recover(sale.session,1,"restore",1);
   assertEquals(50,armory.admissionPlan(sale.session,sale.sku).rules().stream().filter(r->r.getRuleCode().equals("DECISION_POLICY")).findFirst().get().getConfig().get("review"));
  }finally{db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DECISION_POLICY'",original);}
 }
 @Test void activeRequestsUseSharedDatabaseGateLocks()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"ready",1);open(sale);
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch held=new CountDownLatch(2);
  try{
   List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{
    new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
     armory.assertCanCreate(sale.session,sale.sku);held.countDown();
     try{assertTrue(held.await(5,TimeUnit.SECONDS),"Normal requests must acquire shared gate locks concurrently");}
     catch(InterruptedException e){throw new IllegalStateException(e);}
    });
   }));for(Future<?> task:tasks)task.get();
  }finally{pool.shutdownNow();}
 }
 @Test void invalidRuleConfigurationNeverPublishesReady(){
  Sale sale=sale();String original=db.one("SELECT config FROM tb_risk_rule WHERE rule_code='DEVICE_ACCOUNTS'").get("config").toString();
  try{
   db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DEVICE_ACCOUNTS'","{\"feature\":\"deviceAccounts\"}");
   assertThrows(BusinessException.class,()->armory.assemble(sale.session,0,"invalid",1));
   assertEquals("FAILED",armory.status(sale.session).get("state"));assertNull(redis.opsForValue().get(TicketSaleArmory.prefix(sale.session)+"active-version"));
  }finally{db.jdbc.update("UPDATE tb_risk_rule SET config=? WHERE rule_code='DEVICE_ACCOUNTS'",original);}
 }
 @Autowired TicketController controller;
 @Test void actualControllerUsesFrozenRulesAndPersistsReviewVersion()throws Exception{
  Sale sale=sale();Map<String,Object> original=db.one("SELECT * FROM tb_risk_rule WHERE rule_code='DEVICE_ACCOUNTS'");
  try{
   db.jdbc.update("UPDATE tb_risk_rule SET enabled=true,weight=60,version=version+1,config=? WHERE rule_code='DEVICE_ACCOUNTS'","{\"feature\":\"deviceAccounts\",\"threshold\":0,\"fast\":false}");
   armory.assemble(sale.session,0,"frozen",1);
  }finally{db.jdbc.update("UPDATE tb_risk_rule SET enabled=?,weight=?,version=?,config=? WHERE rule_code='DEVICE_ACCOUNTS'",original.get("enabled"),original.get("weight"),original.get("version"),original.get("config"));}
  open(sale);com.ticketing.common.dto.UserDTO user=new com.ticketing.common.dto.UserDTO();user.setId(2L);user.setRole("USER");
  com.ticketing.common.context.UserHolder.saveUser(user);
  try{
   org.springframework.mock.web.MockHttpServletRequest http=new org.springframework.mock.web.MockHttpServletRequest();http.setRemoteAddr("127.0.0.1");
   com.ticketing.common.Result result=controller.reserve(request(sale,"controller"),http);assertTrue(result.getSuccess());
   String order=((Map<?,?>)result.getData()).get("orderId").toString();
   Map<String,Object> snapshot=db.one("SELECT * FROM tb_risk_decision WHERE order_id=? AND phase='ADMISSION'",order);
   assertEquals("REVIEW",snapshot.get("decision"));assertEquals(60,((Number)snapshot.get("score")).intValue());
   assertTrue(snapshot.get("agent_analysis").toString().contains("saleAssemblyVersion"));
   consumer.consume("AUTO_PAYMENT:"+order,order,"AUTO_PAYMENT");consumer.consume("ISSUE:"+order,order,"ISSUE");
   assertNotNull(db.one("SELECT id FROM tb_outbox_message WHERE message_key=?","RISK_CHECK:"+order));
  }finally{com.ticketing.common.context.UserHolder.removeUser();}
 }

 @Autowired com.ticketing.order.OrderService orders;
 @Test void pendingReleaseMustCompleteBeforeRecovery()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);open(sale);
  String order=reserve(sale,"cancel-before-recovery").get("orderId").toString();orders.cancel(order,2);
  assertEquals(33,sqlStock(sale.sku));assertEquals(32,redisStock(sale.sku));
  assertThrows(BusinessException.class,()->armory.recover(sale.session,1,"too-early",1));
  assertEquals(32,redisStock(sale.sku));consumer.consume("RELEASE:"+order,order,"RELEASE");
  armory.recover(sale.session,2,"settled",1);consumer.consume("RELEASE:"+order,order,"RELEASE");
  assertEquals(33,redisStock(sale.sku));assertEquals(33,sqlStock(sale.sku));
 }
 @Test void inconsistentPurchaseLedgerFailsClosedWithoutRefilling(){
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);
  db.jdbc.update("INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(2,?,1)",sale.sku);
  assertThrows(BusinessException.class,()->armory.recover(sale.session,1,"bad-ledger",1));
  assertEquals("FAILED",armory.status(sale.session).get("state"));assertEquals(33,redisStock(sale.sku));
 }
 @Test void expiredWorkerCanBeRecoveredAndOldOperationCannotRepublish(){
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);
  db.jdbc.update("INSERT INTO tb_ticket_sale_version(session_id,version,request_id,operation,status,manifest,operator_id) VALUES(?,2,'abandoned','ASSEMBLE','PREPARING','',1)",sale.session);
  db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state='PREPARING',version=2,request_id='abandoned',lease_until=? WHERE session_id=?",new Timestamp(System.currentTimeMillis()-180000),sale.session);
  armory.recover(sale.session,2,"restart",1);
  assertEquals(3,((Number)armory.status(sale.session).get("active_version")).longValue());
  assertEquals("FAILED",armory.assemble(sale.session,1,"abandoned",1).get("status"));
  assertEquals(3,((Number)armory.status(sale.session).get("version")).longValue());assertEquals(33,redisStock(sale.sku));
 }

 @Test void staleRedisWorkerCannotOverwriteNewStockOrActiveVersion(){
  Sale sale=sale();armory.assemble(sale.session,0,"v1",1);armory.recover(sale.session,1,"v2",1);
  Map<String,Object> stale=new HashMap<>();stale.put("replace",false);
  stale.put("stocks",Collections.singletonMap(InventoryService.prefix(sale.sku)+"stock:bucket:0","999"));
  stale.put("users",Collections.emptyMap());stale.put("markers",Collections.emptyMap());stale.put("pending",Collections.emptyList());stale.put("cleanup",Collections.emptyList());
  assertThrows(BusinessException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->
    inventory.publishAssembly(skus.findById(sale.sku),1,stale)));
  assertThrows(BusinessException.class,()->inventory.claimAssembly(sale.sku,1));
  assertEquals(33,redisStock(sale.sku));
  org.springframework.data.redis.core.script.DefaultRedisScript<Long> script=new org.springframework.data.redis.core.script.DefaultRedisScript<>();
  script.setLocation(new org.springframework.core.io.ClassPathResource("lua/sale_manifest_publish.lua"));script.setResultType(Long.class);
  String p=TicketSaleArmory.prefix(sale.session);
  assertEquals(-1L,redis.execute(script,Arrays.asList(p+"publication-fence",p+"manifest:v1",p+"active-version"),"1","{}"));
  assertEquals("2",redis.opsForValue().get(p+"active-version"));
 }

 @Autowired com.ticketing.event.CatalogAdminService catalog;
 @Test void operationalSaleStatusDoesNotInvalidateStaticAssembly()throws Exception{
  Sale sale=sale();armory.assemble(sale.session,0,"ready",1);open(sale);
  catalog.update(sale.sku,"OFF_SALE",null);
  assertThrows(BusinessException.class,()->armory.admissionPlan(sale.session,sale.sku));
  catalog.update(sale.sku,"ON_SALE",null);
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(!"ON_SALE".equals(redis.opsForHash().get(InventoryService.prefix(sale.sku)+"config","status")) && System.nanoTime()<deadline)Thread.sleep(20);
  assertEquals(1,armory.admissionPlan(sale.session,sale.sku).version());
 }

}
