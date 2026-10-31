from pathlib import Path
root=Path(__file__).parent
def put(path,s):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s.strip()+'\n',encoding='utf-8')
def test(name,s):put('ticket-risk-platform/src/test/java/com/ticketing/'+name+'.java',s)
test('order/OrderStateMachineTest','''
package com.ticketing.order;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
class OrderStateMachineTest {
 @Test void terminalStatesRejectReplay(){for(OrderStatus from:Arrays.asList(OrderStatus.CANCELLED,OrderStatus.REFUNDED,OrderStatus.RISK_BLOCKED,OrderStatus.PAY_TIMEOUT))for(OrderStatus to:OrderStatus.values())assertFalse(OrderStateMachine.permits(from,to));}
 @Test void paymentCannotSkipRisk(){assertFalse(OrderStateMachine.permits(OrderStatus.CREATED,OrderStatus.PAID));assertFalse(OrderStateMachine.permits(OrderStatus.RISK_REVIEW,OrderStatus.PAYING));assertTrue(OrderStateMachine.permits(OrderStatus.WAITING_PAYMENT,OrderStatus.PAYING));}
 @Test void compareAndSwapRejectsStaleVersion(){JdbcTemplate jdbc=mock(JdbcTemplate.class);Db db=new Db(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());Map<String,Object> order=new HashMap<>();order.put("id","o1");order.put("status","CREATED");order.put("version",0);assertThrows(BusinessException.class,()->new OrderStateMachine(db).transition(order,OrderStatus.RISK_CHECKING));assertEquals("CREATED",order.get("status"));}
}
''')
test('risk/RiskRuleEngineTest','''
package com.ticketing.risk;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RiskRuleEngineTest {
 private RiskRule rule(boolean enabled,double threshold,int weight){RiskRule r=new RiskRule();r.setRuleCode("DEVICE_FREQUENCY");r.setEnabled(enabled);r.setWeight(weight);r.setVersion(3);Map<String,Object> c=new HashMap<>();c.put("feature","deviceMinute");c.put("threshold",threshold);c.put("fast",true);r.setConfig(c);return r;}
 @Test void thresholdsAndVersionAreDataDriven(){RiskRuleEngine e=new RiskRuleEngine();Map<String,Double> f=Collections.singletonMap("deviceMinute",60d);RiskScore score=e.evaluate(f,Arrays.asList(rule(true,60,100)),false);assertEquals(100,score.getValue());assertEquals(Arrays.asList("DEVICE_FREQUENCY@3"),score.getMatchedRules());assertEquals(0,e.evaluate(f,Arrays.asList(rule(true,61,100)),false).getValue());}
 @Test void disabledRulesDoNotMatch(){assertEquals(0,new RiskRuleEngine().evaluate(Collections.singletonMap("deviceMinute",1000d),Arrays.asList(rule(false,60,100)),true).getValue());}
 @Test void decisionBoundaries(){RiskRuleEngine e=new RiskRuleEngine();assertEquals(RiskDecision.PASS,e.decide(49,50,100));assertEquals(RiskDecision.REVIEW,e.decide(50,50,100));assertEquals(RiskDecision.BLOCK,e.decide(100,50,100));}
 @Test void newDeviceConditionIsRequired(){RiskRule r=rule(true,60,60);r.getConfig().put("requiresNewDevice",true);assertEquals(0,new RiskRuleEngine().evaluate(Collections.singletonMap("deviceMinute",100d),Arrays.asList(r),false).getValue());}
}
''')
test('payment/WalletConcurrencyTest','''
package com.ticketing.payment;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.review.ManualReviewService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
class WalletConcurrencyTest {
 JdbcTemplate jdbc;TransactionTemplate tx;WalletService wallet;
 @BeforeEach void setup(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,reservation_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),status VARCHAR,version INT,trace_id VARCHAR,expires_at TIMESTAMP,updated_at TIMESTAMP)");
  jdbc.execute("CREATE TABLE tb_wallet_account(user_id BIGINT PRIMARY KEY,available_balance DECIMAL(18,2) CHECK(available_balance>=0),version INT)");
  jdbc.execute("CREATE TABLE tb_payment_transaction(id VARCHAR PRIMARY KEY,payment_request_id VARCHAR UNIQUE,order_id VARCHAR,user_id BIGINT,amount DECIMAL(18,2),type VARCHAR,status VARCHAR,success_key VARCHAR UNIQUE,device_id VARCHAR)");
  jdbc.execute("CREATE TABLE tb_wallet_ledger(id VARCHAR PRIMARY KEY,user_id BIGINT,order_id VARCHAR,transaction_id VARCHAR UNIQUE,type VARCHAR,amount DECIMAL(18,2))");
  jdbc.execute("CREATE TABLE tb_manual_review_ticket(id VARCHAR,order_id VARCHAR,phase VARCHAR,status VARCHAR,risk_decision_id VARCHAR,created_at TIMESTAMP)");
  jdbc.execute("CREATE TABLE tb_risk_decision(id VARCHAR,context_key VARCHAR)");
  jdbc.update("INSERT INTO tb_wallet_account VALUES(1,100,0)");
  for(int i=0;i<3;i++)jdbc.update("INSERT INTO tb_ticket_order VALUES(?,?,1,80,'WAITING_PAYMENT',0,'trace',?,CURRENT_TIMESTAMP)","order"+i,"r"+i,new java.sql.Timestamp(System.currentTimeMillis()+600000));
  Db db=new Db(jdbc,new ObjectMapper());OrderStateMachine machine=new OrderStateMachine(db);OutboxService outbox=mock(OutboxService.class);WorkflowExecutionService workflow=mock(WorkflowExecutionService.class);SimpleMeterRegistry metrics=new SimpleMeterRegistry();OrderService orders=new OrderService(db,machine,outbox,workflow,metrics);
  RiskDecisionService risk=mock(RiskDecisionService.class);when(risk.evaluate(anyMap(),anyString(),anyString(),anyString())).thenReturn(Collections.singletonMap("decision","PASS"));
  wallet=new WalletService(db,orders,machine,risk,mock(ManualReviewService.class),outbox,workflow,metrics);
 }
 private PaymentRequest request(String id){PaymentRequest r=new PaymentRequest();r.setPaymentRequestId(id);r.setDeviceId("device");return r;}
 private List<Map<String,Object>> concurrent(java.util.function.IntFunction<Map<String,Object>> task,int n)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(16);try{List<Callable<Map<String,Object>>> jobs=IntStream.range(0,n).mapToObj(i->(Callable<Map<String,Object>>)()->tx.execute(status->task.apply(i))).collect(Collectors.toList());List<Map<String,Object>> results=new ArrayList<>();for(Future<Map<String,Object>> f:pool.invokeAll(jobs))results.add(f.get());return results;}finally{pool.shutdownNow();}}
 @Test void sameRequestOneDebitAndStableResponse()throws Exception{List<Map<String,Object>> results=concurrent(i->wallet.pay("order0",1,request("pay1"),"127.0.0.1"),100);assertEquals(1,results.stream().map(r->r.get("id")).distinct().count());assertEquals(20,jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tb_wallet_ledger",Integer.class));}
 @Test void differentOrdersCannotOverdraw()throws Exception{concurrent(i->wallet.pay("order"+i,1,request("p"+i),"127.0.0.1"),3);assertEquals(20,jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE status='SUCCESS'",Integer.class));}
 @Test void sameIdCannotBeReusedForAnotherOrder(){tx.execute(s->wallet.pay("order0",1,request("same"),"127.0.0.1"));assertThrows(RuntimeException.class,()->tx.execute(s->wallet.pay("order1",1,request("same"),"127.0.0.1")));assertEquals(20,jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account",Integer.class));}
 @Test void failedPaymentIsIdempotent(){jdbc.update("UPDATE tb_wallet_account SET available_balance=0");Map<String,Object> first=tx.execute(s->wallet.pay("order0",1,request("failed"),"127.0.0.1"));jdbc.update("UPDATE tb_wallet_account SET available_balance=100");Map<String,Object> again=tx.execute(s->wallet.pay("order0",1,request("failed"),"127.0.0.1"));assertEquals("FAILED",again.get("status"));assertEquals(first.get("id"),again.get("id"));assertEquals(100,jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account",Integer.class));}
}
''')
test('integration/TicketPlatformIT','''
package com.ticketing.integration;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.*;
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
@Testcontainers
@SpringBootTest(classes=TicketPlatformApplication.class,properties={"spring.datasource.initialization-mode=always","ticketing.mq.enabled=false","ticketing.jobs.enabled=false","spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
class TicketPlatformIT {
 @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withDatabaseName("ticketing");
 @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);r.add("spring.redis.host",redis::getHost);r.add("spring.redis.port",()->redis.getMappedPort(6379));}
 @Autowired Db db;@Autowired InventoryService inventory;@Autowired ReservationIntentService intents;@Autowired ReservationTransactionService reservations;@Autowired TicketEventConsumer consumer;@Autowired WalletService wallet;@Autowired StringRedisTemplate redisClient;
 private String sku(int stock,int limit){String id=UUID.randomUUID().toString();db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status) VALUES(?,'session-1','Integration',10,?,?,?, ?,?,'ON_SALE')",id,stock,stock,new java.sql.Timestamp(System.currentTimeMillis()-1000),new java.sql.Timestamp(System.currentTimeMillis()+3600000),limit);inventory.configure(db.one("SELECT * FROM tb_ticket_sku WHERE id=?",id));return id;}
 private ReserveRequest request(String sku,long user,String id){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId(user);r.setTicketSkuId(sku);r.setEventSessionId("session-1");r.setQuantity(1);r.setDeviceId("device-"+user);return r;}
 private Map<String,Object> reserve(ReserveRequest r){intents.create(r,"127.0.0.1");return reservations.reserve(r.getRequestId(),"session-1");}
 private List<Object> concurrent(int n,java.util.function.IntFunction<Object> work)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(32);try{List<Callable<Object>> jobs=new ArrayList<>();for(int i=0;i<n;i++){final int index=i;jobs.add(()->{try{return work.apply(index);}catch(com.ticketing.common.BusinessException e){return e;}});}List<Object> result=new ArrayList<>();for(Future<Object> f:pool.invokeAll(jobs))result.add(f.get());return result;}finally{pool.shutdownNow();}}
 @Test void concurrencyCannotOversell()throws Exception{String sku=sku(20,2);List<Object> outcomes=concurrent(100,i->reserve(request(sku,i+100,"flash-"+i)));assertEquals(20,outcomes.stream().filter(x->x instanceof Map).count());assertEquals(0,db.jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku WHERE id=?",Integer.class,sku));assertEquals("0",redisClient.opsForValue().get(InventoryService.prefix(sku)+"stock"));}
 @Test void duplicateRequestCreatesOneOrderAndOutbox()throws Exception{String sku=sku(10,2);ReserveRequest r=request(sku,1,"duplicate");concurrent(100,i->reserve(r));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation WHERE request_id='duplicate'",Integer.class));String order=db.one("SELECT order_id FROM tb_reserve_request WHERE request_id='duplicate'").get("order_id").toString();assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE aggregate_id=?",Integer.class,order));}
 @Test void userLimitHoldsAcrossDifferentRequests()throws Exception{String sku=sku(100,2);concurrent(100,i->reserve(request(sku,2,"limit-"+i)));assertEquals(2,db.jdbc.queryForObject("SELECT SUM(quantity) FROM tb_reservation WHERE sku_id=?",Integer.class,sku));}
 @Test void duplicateMqAndReleaseAreIdempotent(){String sku=sku(10,2);Map<String,Object> result=reserve(request(sku,1,"mq"));String order=result.get("orderId").toString();consumer.consume("RISK_CHECK:"+order,order,"RISK_CHECK");consumer.consume("RISK_CHECK:"+order,order,"RISK_CHECK");assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record WHERE message_key=?",Integer.class,"RISK_CHECK:"+order));String reservation=result.get("reservationId").toString();inventory.release(sku,1,reservation);inventory.release(sku,1,reservation);assertEquals("10",redisClient.opsForValue().get(InventoryService.prefix(sku)+"stock"));}
 @Test void luaRejectsChangedFingerprintAndTokenUnlockIsSafe(){String sku=sku(10,2);ReserveRequest r=request(sku,1,"fingerprint");Map<String,Object> intent=intents.create(r,"127.0.0.1");assertEquals(1,inventory.reserve(intent));assertEquals(2,inventory.reserve(intent));intent.put("fingerprint","different");assertEquals(-4,inventory.reserve(intent));com.ticketing.workflow.lock.WorkflowLockService locks=new com.ticketing.workflow.lock.WorkflowLockService(redisClient);assertTrue(locks.tryLock("token-test","a"));locks.unlock("token-test","b");assertTrue(locks.isHeldBy("token-test","a"));locks.unlock("token-test","a");assertFalse(locks.isHeldBy("token-test","a"));}
}
''')
p=root/'ticket-risk-platform/pom.xml';s=p.read_text(encoding='utf-8');s=s.replace('</project>','''
    <profiles><profile><id>integration</id><build><plugins><plugin>
      <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>2.22.2</version>
      <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
    </plugin></plugins></build></profile></profiles>
</project>''');p.write_text(s,encoding='utf-8')
put('ticket-risk-platform/src/test/resources/logback-test.xml','<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{24} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')
