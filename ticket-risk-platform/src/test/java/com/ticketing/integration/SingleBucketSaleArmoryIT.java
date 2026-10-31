package com.ticketing.integration;
import com.ticketing.TicketPlatformApplication;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.sale.*;
import com.ticketing.ticket.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(classes=TicketPlatformApplication.class,properties={
 "spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always",
 "ticketing.sale.armory.enabled=true","ticketing.inventory.bucket-count=1","ticketing.mq.enabled=false",
 "ticketing.jobs.enabled=false","ticketing.metrics.snapshot.enabled=false",
 "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
class SingleBucketSaleArmoryIT {
 static {org.springframework.core.SpringProperties.setProperty("spring.jdbc.getParameterType.ignore","true");}
 @Container static MySQLContainer<?> mysql=new MySQLContainer<>(System.getProperty("ticketing.test.mysql-image","mysql:8.0"))
  .withUrlParam("useSSL","false").withUrlParam("serverTimezone","UTC").withUrlParam("useLegacyDatetimeCode","false").withDatabaseName("single_sale");
 @Container static GenericContainer<?> cache=new GenericContainer<>(System.getProperty("ticketing.test.redis-image","redis:7-alpine")).withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);r.add("spring.redis.host",cache::getHost);r.add("spring.redis.port",()->cache.getMappedPort(6379));}
 @Autowired Db db;@Autowired TicketSaleArmory armory;@Autowired StringRedisTemplate redis;
 @Autowired ReservationIntentService intents;@Autowired ReservationTransactionService reservations;
 @Test void stockCasVersionsDoNotInvalidateAssemblyAndRecoveryKeepsRemainingStock()throws Exception{
  String session=UUID.randomUUID().toString(),sku=UUID.randomUUID().toString();long start=(System.currentTimeMillis()/1000+6)*1000;
  db.jdbc.update("INSERT INTO tb_event_session(id,event_id,starts_at) VALUES(?,'event-1',?)",session,new Timestamp(start+86400000));
  db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,purchase_limit,sale_start_time,sale_end_time,status) VALUES(?,?,'Single',10,4,4,2,?,?,'ON_SALE')",sku,session,new Timestamp(start),new Timestamp(start+3600000));
  armory.assemble(session,0,"ready",1);long delay=start-System.currentTimeMillis()+50;if(delay>0)Thread.sleep(delay);
  for(int i=0;i<2;i++){
   SaleManifest plan=armory.admissionPlan(session,sku);ReserveRequest request=new ReserveRequest();request.setRequestId("single-"+i);
   request.setUserId(2L);request.setEventSessionId(session);request.setTicketSkuId(sku);request.setQuantity(1);request.setDeviceId("single-device");
   Map<String,Object> entry=new HashMap<>();entry.put("decision","PASS");entry.put("saleVersion",plan.version());
   intents.create(request,"127.0.0.1");reservations.reserve(request.getRequestId(),session,entry);
  }
  assertEquals(2,db.jdbc.queryForObject("SELECT version FROM tb_ticket_sku WHERE id=?",Integer.class,sku));
  assertEquals("2",redis.opsForValue().get(InventoryService.prefix(sku)+"stock"));
  redis.delete(InventoryService.prefix(sku)+"stock");armory.recover(session,1,"recover",1);
  assertEquals("2",redis.opsForValue().get(InventoryService.prefix(sku)+"stock"));
  assertEquals("2",redis.opsForValue().get(InventoryService.prefix(sku)+"user:2"));
  assertEquals(2,armory.admissionPlan(session,sku).version());
 }
 @Autowired InventoryService inventory;
 @Test void noSalesAfterOpeningStillCannotTriggerAutomaticInitialRefill()throws Exception{
  String session=UUID.randomUUID().toString(),sku=UUID.randomUUID().toString();long start=(System.currentTimeMillis()/1000+6)*1000;
  db.jdbc.update("INSERT INTO tb_event_session(id,event_id,starts_at) VALUES(?,'event-1',?)",session,new Timestamp(start+86400000));
  db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,purchase_limit,sale_start_time,sale_end_time,status) VALUES(?,?,'Pristine',10,4,4,2,?,?,'ON_SALE')",sku,session,new Timestamp(start),new Timestamp(start+3600000));
  armory.assemble(session,0,"ready",1);long delay=start-System.currentTimeMillis()+50;if(delay>0)Thread.sleep(delay);
  String key=InventoryService.prefix(sku)+"stock";redis.delete(key);
  assertThrows(com.ticketing.common.BusinessException.class,()->inventory.ensureReady(sku));assertNull(redis.opsForValue().get(key));
  assertThrows(com.ticketing.common.BusinessException.class,()->armory.assemble(session,1,"not-recovery",1));assertNull(redis.opsForValue().get(key));
  armory.recover(session,2,"explicit-recovery",1);assertEquals("4",redis.opsForValue().get(key));
 }

}
