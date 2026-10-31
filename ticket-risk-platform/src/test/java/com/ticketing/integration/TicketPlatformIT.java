package com.ticketing.integration;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import com.ticketing.TicketPlatformApplication;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.ticket.*;
import com.ticketing.mq.TicketEventConsumer;
import com.ticketing.payment.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(classes=TicketPlatformApplication.class,properties={"spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always","ticketing.mq.enabled=false","ticketing.jobs.enabled=false","spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
class TicketPlatformIT {
 // Connector/J 5.1 cannot reliably obtain null-parameter metadata from MySQL 8.
 // Test JVM only; retain actual null values rather than changing business fixtures.
 static { org.springframework.core.SpringProperties.setProperty("spring.jdbc.getParameterType.ignore", "true"); }

 @Container static MySQLContainer<?> mysql=new MySQLContainer<>(System.getProperty("ticketing.test.mysql-image", "mysql:8.0")).withUrlParam("generateSimpleParameterMetadata", "true").withUrlParam("useSSL", "false").withUrlParam("serverTimezone", "UTC").withUrlParam("useLegacyDatetimeCode", "false").withDatabaseName("ticketing");
 @Container static GenericContainer<?> redis=new GenericContainer<>(System.getProperty("ticketing.test.redis-image", "redis:7-alpine")).withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);r.add("spring.redis.host",redis::getHost);r.add("spring.redis.port",()->redis.getMappedPort(6379));}
 @Autowired Db db;@Autowired InventoryService inventory;@Autowired ReservationIntentService intents;@Autowired ReservationTransactionService reservations;@Autowired TicketEventConsumer consumer;@Autowired WalletService wallet;@Autowired StringRedisTemplate redisClient;
 private String sku(int stock,int limit){String id=UUID.randomUUID().toString();db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status) VALUES(?,'session-1','Integration',10,?,?,?, ?,?,'ON_SALE')",id,stock,stock,new java.sql.Timestamp(System.currentTimeMillis()-1000),new java.sql.Timestamp(System.currentTimeMillis()+3600000),limit);inventory.configure(id);return id;}
 private ReserveRequest request(String sku,long user,String id){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId(user);r.setTicketSkuId(sku);r.setEventSessionId("session-1");r.setQuantity(1);r.setDeviceId("device-"+user);return r;}
 private Map<String,Object> reserve(ReserveRequest r){intents.create(r,"127.0.0.1");return reservations.reserve(r.getRequestId(),"session-1");}
 private List<Object> concurrent(int n,java.util.function.IntFunction<Object> work)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(32);try{List<Callable<Object>> jobs=new ArrayList<>();for(int i=0;i<n;i++){final int index=i;jobs.add(()->{try{return work.apply(index);}catch(com.ticketing.common.BusinessException e){return e;}});}List<Object> result=new ArrayList<>();for(Future<Object> f:pool.invokeAll(jobs))result.add(f.get());return result;}finally{pool.shutdownNow();}}
 @Test void concurrencyCannotOversell()throws Exception{String sku=sku(20,2);List<Object> outcomes=concurrent(100,i->reserve(request(sku,i+100,"flash-"+i)));assertEquals(20,outcomes.stream().filter(x->x instanceof Map).count());assertEquals(0,db.jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?",Integer.class,sku));assertEquals("0",redisClient.opsForValue().get(InventoryService.prefix(sku)+"stock"));}
 @Test void duplicateRequestCreatesOneOrderAndOutbox()throws Exception{String sku=sku(10,2);ReserveRequest r=request(sku,1,"duplicate");concurrent(100,i->reserve(r));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation WHERE request_id='duplicate'",Integer.class));String order=db.one("SELECT order_id FROM tb_reserve_request WHERE request_id='duplicate'").get("order_id").toString();assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE aggregate_id=?",Integer.class,order));}
 @Test void userLimitHoldsAcrossDifferentRequests()throws Exception{String sku=sku(100,2);concurrent(100,i->reserve(request(sku,2,"limit-"+i)));assertEquals(2,db.jdbc.queryForObject("SELECT SUM(quantity) FROM tb_reservation WHERE sku_id=?",Integer.class,sku));}
 @Test void duplicatePostIssueRiskAndIssueAreIdempotent(){
  String sku=sku(10,2);Map<String,Object> result=reserve(request(sku,1,"mq"));
  String order=result.get("orderId").toString();
  consumer.consume("AUTO_PAYMENT:"+order,order,"AUTO_PAYMENT");
  consumer.consume("ISSUE:"+order,order,"ISSUE");
  consumer.consume("ISSUE:"+order,order,"ISSUE");
  assertEquals("ISSUED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));
  consumer.consume("RISK_CHECK:"+order,order,"RISK_CHECK");
  consumer.consume("RISK_CHECK:"+order,order,"RISK_CHECK");
  assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record WHERE message_key=?",Integer.class,"RISK_CHECK:"+order));
  assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_issued_ticket WHERE order_id=?",Integer.class,order));
 }
 @Test void duplicateReleaseOfUnpaidReservationIsIdempotent(){
  String sku=sku(10,2);Map<String,Object> result=reserve(request(sku,1,"release"));
  String reservation=result.get("reservationId").toString();
  inventory.release(sku,1,reservation);inventory.release(sku,1,reservation);
  assertEquals("10",redisClient.opsForValue().get(InventoryService.prefix(sku)+"stock"));
 }
 @Test void luaRejectsChangedFingerprintAndTokenUnlockIsSafe(){String sku=sku(10,2);ReserveRequest r=request(sku,1,"fingerprint");ReservationIntent intent=intents.create(r,"127.0.0.1");assertEquals(1,inventory.reserve(intent));assertEquals(2,inventory.reserve(intent));intent.setFingerprint("different");assertEquals(-4,inventory.reserve(intent));com.ticketing.workflow.lock.WorkflowLockService locks=new com.ticketing.workflow.lock.WorkflowLockService(redisClient);assertTrue(locks.tryLock("token-test","a"));locks.unlock("token-test","b");assertTrue(locks.isHeldBy("token-test","a"));locks.unlock("token-test","a");assertFalse(locks.isHeldBy("token-test","a"));}
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Autowired io.micrometer.core.instrument.MeterRegistry registry;
 @Test void configurationRollsBackWithoutRedisPublication(){
  String sku=sku(10,2);String key=InventoryService.prefix(sku)+"config";
  new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
   db.jdbc.update("UPDATE tb_ticket_sku SET status='OFF_SALE' WHERE id=?",sku);
   inventory.configure(sku);
   assertEquals("ON_SALE",redisClient.opsForHash().get(key,"status"));status.setRollbackOnly();
  });
  assertEquals("ON_SALE",redisClient.opsForHash().get(key,"status"));
  assertEquals("ON_SALE",db.one("SELECT status FROM tb_ticket_sku WHERE id=?",sku).get("status"));
 }
 @Test void committedConfigurationPublishesAfterTransaction() throws Exception {
  String sku=sku(10,2);String key=InventoryService.prefix(sku)+"config";
  new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
   db.jdbc.update("UPDATE tb_ticket_sku SET status='OFF_SALE' WHERE id=?",sku);inventory.configure(sku);
   assertEquals("ON_SALE",redisClient.opsForHash().get(key,"status"));
  });
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  while(!"OFF_SALE".equals(redisClient.opsForHash().get(key,"status")) && System.nanoTime()<deadline)Thread.sleep(20);
  assertEquals("OFF_SALE",redisClient.opsForHash().get(key,"status"));
 }
 @Test void lostTradingStockIsNeverReinitialized(){
  String sku=sku(10,2);reserve(request(sku,1,"lost-stock"));
  String key=InventoryService.prefix(sku)+"stock";redisClient.delete(key);
  assertThrows(com.ticketing.common.BusinessException.class,()->inventory.ensureReady(sku));
  assertFalse(Boolean.TRUE.equals(redisClient.hasKey(key)));
 }
 @Test void businessMetricsAreWiredIntoRealReservation(){
  String sku=sku(10,2);reserve(request(sku,1,"metrics"));
  assertTrue(registry.get("ticketing.operation.duration").tags("operation","order_transaction","outcome","success").timer().count()>0);
  assertTrue(registry.get("ticketing.operation.duration").tags("operation","redis_reserve","outcome","success").timer().count()>0);
 }

}
