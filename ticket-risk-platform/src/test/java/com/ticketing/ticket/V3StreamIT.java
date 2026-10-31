package com.ticketing.ticket;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.risk.RiskAgentClient;
import com.ticketing.sale.TicketSaleArmory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V3 end to end on real MySQL + Redis with the real Spring context and the unchanged risk / inventory Lua / HELD code.
 * The consumer is driven synchronously (autostart=false). Requires runtime_protocol=4 and redis_lane_enabled=1 in the
 * owned schema. Opt-in: -Dticketing.v3.integration=true
 */
@EnabledIfSystemProperty(named="ticketing.v3.integration",matches="true")
@SpringBootTest(classes=TicketPlatformApplication.class,properties={
 "spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always",
 "ticketing.reservations-v2.enabled=true","ticketing.sale.armory.enabled=true","ticketing.inventory.bucket-count=16",
 "ticketing.reservations-v2.poll-ms=3600000","ticketing.risk.summary.enabled=true",
 "ticketing.mq.enabled=false","ticketing.jobs.enabled=false","ticketing.metrics.snapshot.enabled=false",
 "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
 "ticketing.pools.sale.core=4","ticketing.pools.sale.max=4","ticketing.pools.sale.queue=0",
 "ticketing.reservations-v2.attempts.enabled=true","ticketing.reservations-v2.dispatch.isolated=true",
 "ticketing.reservations-v2.attempts.ordered-lua=true","ticketing.reservations-v2.attempts.redis-ordered-lane=true",
 "ticketing.reservations-v3.enabled=true","ticketing.reservations-v3.autostart=false","ticketing.reservations-v3.consumer=it"})
class V3StreamIT {
    static {org.springframework.core.SpringProperties.setProperty("spring.jdbc.getParameterType.ignore","true");}
    @Autowired Db db;@Autowired TicketSaleArmory armory;@Autowired StringRedisTemplate redis;
    @Autowired ReservationV2Service service;@Autowired ReservationDispatch queue;@Autowired ReservationV2Transactions transactions;
    @Autowired ReservationIngress ingress;@Autowired ReservationAttemptStore attempts;@Autowired ReservationModeController modes;
    @Autowired V3Redis v3;@Autowired V3StreamWorker worker;@Autowired V3JobStarter starter;@Autowired ReservationV2Controller controller;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @org.springframework.boot.test.mock.mockito.SpyBean(proxyTargetAware=true) InventoryService inventory;
    @org.springframework.boot.test.mock.mockito.MockBean RiskAgentClient agent;
    static AtomicLong users=new AtomicLong(70000);
    static class Sale {String event=Db.id(),session=Db.id(),sku=Db.id();long start;}

    @BeforeEach void freshV3Keys(){
        reset(agent,inventory);
        Set<String> keys=redis.keys("{tkv3}*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        v3.ensureGroup();v3.mode("OPEN");
        assertEquals(4L,db.jdbc.queryForObject("SELECT runtime_protocol FROM tb_reservation_dispatch WHERE id=1",Long.class));
    }
    Sale sale()throws Exception{
        Sale s=new Sale();s.start=(System.currentTimeMillis()/1000+15)*1000;
        db.jdbc.update("INSERT INTO tb_event(id,name,venue,status) VALUES(?,'V3 fixture','test','ON_SALE')",s.event);
        db.jdbc.update("INSERT INTO tb_event_session VALUES(?,?,?)",s.session,s.event,new Timestamp(s.start+86400000));
        db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES(?,?,'fixture',10,100,100,?,?,2,'ON_SALE',0)",s.sku,s.session,new Timestamp(s.start),new Timestamp(s.start+3600000));
        armory.assemble(s.session,0,"assemble-"+s.session,1);
        long wait=s.start-System.currentTimeMillis()+20;if(wait>0)Thread.sleep(wait);return s;
    }
    ReserveRequest request(Sale sale){
        long user=users.incrementAndGet();db.jdbc.update("INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES(?,?,'fixture','USER','2025-01-01')",user,"138"+String.format("%08d",user));
        db.jdbc.update("INSERT INTO tb_wallet_account(user_id,available_balance) VALUES(?,10000)",user);
        ReserveRequest r=new ReserveRequest();r.setRequestId(Db.id());r.setUserId(user);r.setEventSessionId(sale.session);r.setTicketSkuId(sale.sku);r.setQuantity(1);r.setDeviceId("device-"+user);return r;
    }
    ReserveRequest sameUser(ReserveRequest r){ReserveRequest x=new ReserveRequest();x.setRequestId(Db.id());x.setUserId(r.getUserId());x.setEventSessionId(r.getEventSessionId());x.setTicketSkuId(r.getTicketSkuId());x.setQuantity(1);x.setDeviceId(r.getDeviceId());return x;}
    Map<String,Object> accept(ReserveRequest r){return service.registerAttempt(r,"ip-"+r.getUserId());}
    long stock(Sale s){return db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id=?",Long.class,s.sku);}
    long redisStock(Sale s){long n=0;for(int i=0;i<16;i++)n+=Long.parseLong(redis.opsForValue().get(InventoryService.prefix(s.sku)+"stock:bucket:"+i));return n;}
    int orders(ReserveRequest r){return db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation WHERE request_id=?",Integer.class,r.getRequestId());}
    Map<String,Object> job(ReserveRequest r){return queue.byRequest(r.getRequestId());}
    Map<String,Object> status(ReserveRequest r){return ingress.status(r.getRequestId(),r.getUserId(),System.currentTimeMillis());}
    List<MapRecord<String,Object,Object>> read(){
        Map<String,MapRecord<String,Object,Object>> seen=new LinkedHashMap<>();
        for(MapRecord<String,Object,Object> x:v3.readNew("it",100))seen.put(x.getId().getValue(),x);
        for(MapRecord<String,Object,Object> x:v3.readOwnPending("it",100))seen.put(x.getId().getValue(),x);
        return new ArrayList<>(seen.values());
    }
    /** Drive the consumer synchronously until nothing changes. */
    void drain(){for(int i=0;i<10;i++){List<MapRecord<String,Object,Object>> batch=read();if(batch.isEmpty())return;for(MapRecord<String,Object,Object> m:batch)worker.handle(m);}}
    V3Redis.Message message(ReserveRequest r){
        for(MapRecord<String,Object,Object> x:read())if(r.getRequestId().equals(x.getValue().get("requestId")))return new V3Redis.Message(x.getId().getValue(),x.getValue());
        fail("no message for "+r.getRequestId());return null;
    }
    void assertSettledStream(){assertEquals(0,v3.pendingCount(),"nothing left unacknowledged");assertEquals(0,v3.streamLength(),"settled messages deleted");}

    @Test void normalFlowProcessingReceiptThenExactlyOneHeldOrder()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);
        Map<String,Object> receipt=accept(r);
        assertEquals("PROCESSING",receipt.get("state"));assertEquals(false,receipt.get("reliableAccepted"));assertEquals(false,receipt.get("durable"));
        assertEquals(false,receipt.get("reservationSuccess"));assertEquals(V3Entry.RECEIPT_SEMANTICS,receipt.get("receiptSemantics"));
        assertNull(job(r),"no MySQL row on the HTTP path");
        Map<String,Object> pending=status(r);assertEquals("PROCESSING",pending.get("state"));assertEquals(false,pending.get("authoritative"));assertEquals(false,pending.get("terminal"));
        drain();
        Map<String,Object> done=job(r);assertEquals("RESERVED",done.get("state"));assertEquals(4L,((Number)done.get("attempt_protocol")).longValue());assertEquals(receipt.get("attemptId"),done.get("id"));
        assertEquals("WAITING_PAYMENT",db.one("SELECT status FROM tb_ticket_order WHERE id=?",done.get("order_id")).get("status"));
        assertEquals("COMPLETED",db.one("SELECT status FROM tb_reserve_request WHERE request_id=?",r.getRequestId()).get("status"));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertEquals(1,orders(r));
        Map<String,Object> result=status(r);assertEquals(true,result.get("authoritative"));assertEquals(true,result.get("reservationSuccess"));assertNotNull(result.get("orderId"));
        assertSettledStream();assertEquals("DONE",v3.request(r.getRequestId()).get("state"));
        Map<String,Object> retry=accept(r);assertEquals(false,retry.get("newSubmission"));assertEquals(true,retry.get("reservationSuccess"),"a retry returns the committed MySQL result");
        drain();assertEquals(1,orders(r));assertEquals(99,stock(s));
        assertTrue(metrics.get("attempt.transaction.duration").tags("operation","v3_begin","outcome","success","phase","total").timer().count()>0);
        assertTrue(metrics.get("attempt.v3.begin.sql").tags("operation","insert_job","outcome","success").timer().count()>0);
        assertTrue(metrics.get("attempt.v3.begin.commit_callbacks").tag("outcome","success").timer().count()>0);
    }
    @Test void httpReceiptIs202ProcessingAndModeAdvertisesV3()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);
        String token="v3-"+Db.id();Map<String,String> identity=new HashMap<>();identity.put("id",r.getUserId().toString());identity.put("role","USER");redis.opsForHash().putAll("login:token:"+token,identity);
        org.springframework.test.web.servlet.MockMvc http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new com.ticketing.common.util.RefreshTokenInterceptor(redis),new com.ticketing.common.util.LoginInterceptor()).build();
        com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.test.web.servlet.MvcResult response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tickets/reservations").header("authorization",token).contentType("application/json").content(mapper.writeValueAsString(r))).andReturn();
        assertEquals(202,response.getResponse().getStatus());
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)mapper.readValue(response.getResponse().getContentAsString(),Map.class).get("data");
        assertEquals("PROCESSING",body.get("state"));assertEquals(false,body.get("reliableAccepted"));assertEquals(true,body.get("received"));
        assertEquals(V3Entry.RECEIPT_SEMANTICS,((Map<?,?>)modes.mode().getData()).get("receiptSemantics"));
        Map<String,Object> unknown=ingress.status(Db.id(),r.getUserId(),System.currentTimeMillis());
        assertEquals(false,unknown.get("authoritative"));assertEquals(false,unknown.get("terminal"));assertEquals(false,unknown.get("reservationSuccess"));
    }
    @Test void doubleClickWithNewRequestIdAliasesTheInFlightAttempt()throws Exception{
        Sale s=sale();ReserveRequest a=request(s),b=sameUser(a);
        accept(a);Map<String,Object> second=accept(b);assertEquals("ALIAS",second.get("stage"));
        drain();
        assertEquals("RESERVED",job(a).get("state"));assertNull(job(b),"no second attempt");
        Map<String,Object> viaAlias=status(b);assertEquals(true,viaAlias.get("reservationSuccess"),"alias reads the target's MySQL result");
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertSettledStream();
    }
    @Test void queueTimeoutIsReportedOnlyAfterTheMysqlFenceAndBlocksAnyLaterStart()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);accept(r);
        redis.opsForHash().put(V3Redis.req(r.getRequestId()),"deadline","1"); // deadline passed before any worker started it
        V3Redis.Message m=message(r);
        assertEquals("TIMEOUT",v3.start(m,"t",15000,4).get(0));
        Map<String,Object> beforeFence=status(r);assertEquals("PROCESSING",beforeFence.get("state"),"Redis-only timeout is not a failure");assertEquals(false,beforeFence.get("authoritative"));
        assertEquals(1,v3.pendingCount(),"not acknowledged before the fence");
        drain();
        Map<String,Object> fenced=job(r);assertEquals("QUEUE_TIMEOUT",fenced.get("state"));assertNull(fenced.get("active_owner"));
        Map<String,Object> failed=status(r);assertEquals(true,failed.get("authoritative"));assertEquals(true,failed.get("terminal"));assertEquals(false,failed.get("reservationSuccess"));
        assertSettledStream();
        // Redis loses every V3 key; the client retries with the same request id and it is accepted again.
        redis.delete(redis.keys("{tkv3}*"));v3.ensureGroup();v3.mode("OPEN");
        assertEquals("PROCESSING",accept(r).get("state"));drain();
        assertEquals("QUEUE_TIMEOUT",job(r).get("state"),"terminal row is never restarted");
        assertEquals(0,orders(r));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE request_id=?",Integer.class,r.getRequestId()));
        assertSettledStream();
    }
    @Test void startAndTimeoutFenceAreMutuallyExclusiveInBothOrders()throws Exception{
        Sale s=sale();ReserveRequest first=request(s),second=request(s);accept(first);accept(second);
        V3Redis.Message a=message(first),b=message(second);
        // start committed first -> the fence is not written and nothing may report failure
        assertEquals(V3JobStarter.Kind.OWNED,starter.begin(a,"ta").kind);
        V3JobStarter.Outcome late=starter.fenceTimeout(a);assertEquals(V3JobStarter.Kind.DELEGATED,late.kind);assertEquals("PROCESSING",job(first).get("state"));
        // fence committed first -> a later start sees the terminal row and never processes
        assertEquals(V3JobStarter.Kind.FENCED,starter.fenceTimeout(b).kind);
        V3JobStarter.Outcome refused=starter.begin(b,"tb");assertEquals(V3JobStarter.Kind.TERMINAL,refused.kind);assertEquals("QUEUE_TIMEOUT",refused.state);
        service.processV3(job(first));
        assertEquals("RESERVED",job(first).get("state"));assertEquals("QUEUE_TIMEOUT",job(second).get("state"));assertEquals(0,orders(second));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));
    }
    @Test void concurrentStartAndTimeoutFenceAlwaysHaveExactlyOneWinner()throws Exception{
        Sale s=sale();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            for(int i=0;i<20;i++){
                redis.delete(V3Redis.WAITING); // MySQL-only race: these never pass the Redis START that frees capacity
                ReserveRequest r=request(s);accept(r);V3Redis.Message m=message(r);java.util.concurrent.CyclicBarrier go=new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<V3JobStarter.Outcome> start=pool.submit(()->{go.await();return starter.begin(m,Db.id());});
                java.util.concurrent.Future<V3JobStarter.Outcome> fence=pool.submit(()->{go.await();return starter.fenceTimeout(m);});
                V3JobStarter.Kind a=start.get().kind,b=fence.get().kind;String row=job(r).get("state").toString();
                if(a==V3JobStarter.Kind.OWNED){assertEquals(V3JobStarter.Kind.DELEGATED,b);assertEquals("PROCESSING",row);}
                else{assertEquals(V3JobStarter.Kind.TERMINAL,a);assertEquals(V3JobStarter.Kind.FENCED,b);assertEquals("QUEUE_TIMEOUT",row);}
                assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE request_id=?",Integer.class,r.getRequestId()));
            }
        }finally{pool.shutdownNow();}
    }
    @Test void consumerCrashAfterInventoryLuaIsTakenOverAndCompletesExactlyOnce()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);accept(r);
        AtomicBoolean crash=new AtomicBoolean(true);
        // Vector reserve also decodes its marker inside the ordered transaction. Crash only in final confirmation,
        // after the ordered transaction durably recorded lua_turn_finished, matching this test's existing assertions.
        doAnswer(call->{if(ReservationQualificationOrder.finished(job(r))&&crash.getAndSet(false))throw new Error("simulated JVM death after the inventory Lua");return call.callRealMethod();}).when(inventory).loadRuntimeHold(any(),anyLong());
        V3Redis.Message m=message(r);
        assertThrows(Error.class,()->worker.handle(record(m)));
        Map<String,Object> stuck=job(r);assertEquals("PROCESSING",stuck.get("state"));assertEquals(1,((Number)stuck.get("lua_turn_finished")).intValue());
        assertEquals(99,redisStock(s),"Redis hold exists");assertEquals(100,stock(s),"no MySQL hold yet");assertEquals(1,v3.pendingCount());
        // The zombie's MySQL lease and Redis lease both expire; the message is redelivered.
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=?",new Timestamp(System.currentTimeMillis()-1000),stuck.get("id"));
        redis.opsForHash().put(V3Redis.req(r.getRequestId()),"lease","0");
        drain();
        assertEquals("RESERVED",job(r).get("state"));assertNotNull(stuck.get("lease_token"));
        // The old holder wakes up and tries to finish with its old fence: nothing changes.
        transactions.reserve(stuck);transactions.abort(stuck,"EXECUTION_UNAVAILABLE");
        assertEquals("RESERVED",job(r).get("state"));assertEquals(1,orders(r));assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertSettledStream();
    }
    @Test void mysqlFailureAfterInventoryLuaCompensatesAndIsReportedOnlyAsMysqlFailure()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);accept(r);
        doThrow(new IllegalStateException("simulated MySQL HELD failure")).when(inventory).loadRuntimeHold(any(),anyLong());
        drain();
        Map<String,Object> failed=job(r);assertEquals("FAILED",failed.get("state"));
        assertEquals("FAILED",db.one("SELECT status FROM tb_reserve_request WHERE request_id=?",r.getRequestId()).get("status"));
        assertEquals("ABORTED",redis.opsForHash().get(InventoryService.prefix(s.sku)+"reservation:"+failed.get("reservation_id"),"state"));
        assertEquals(100,stock(s));assertEquals(100,redisStock(s));assertEquals(0,orders(r));
        assertEquals(true,status(r).get("terminal"));assertSettledStream();
    }
    @Test void commitThatSucceededButReportedAnErrorIsNeverCompensated()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);accept(r);AtomicBoolean thrown=new AtomicBoolean();
        doAnswer(call->{Object v=call.callRealMethod();
            // Do not attach to vector reserve's earlier ordered transaction: this test simulates HELD COMMIT loss.
            if(ReservationQualificationOrder.finished(job(r)))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){thrown.set(true);throw new IllegalStateException("simulated lost commit acknowledgement");}});
            return v;}).when(inventory).loadRuntimeHold(any(),anyLong());
        drain();assertTrue(thrown.get(),"the caller saw an error after the commit");
        assertEquals("RESERVED",job(r).get("state"));assertEquals("COMPLETED",db.one("SELECT status FROM tb_reserve_request WHERE request_id=?",r.getRequestId()).get("status"));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertEquals(1,orders(r));assertSettledStream();
    }
    @Test void losingAllV3RedisStateMidFlightNeverDuplicatesOrOversells()throws Exception{
        Sale s=sale();ReserveRequest done=request(s),inFlight=request(s),queued=request(s);
        accept(done);drain();assertEquals("RESERVED",job(done).get("state"));
        accept(inFlight);accept(queued);
        AtomicBoolean crash=new AtomicBoolean(true);
        doAnswer(call->{if(crash.getAndSet(false))throw new Error("crash after Lua");return call.callRealMethod();}).when(inventory).loadRuntimeHold(any(),anyLong());
        assertThrows(Error.class,()->worker.handle(record(message(inFlight))));
        redis.delete(redis.keys("{tkv3}*"));v3.ensureGroup();v3.mode("OPEN"); // dataset loss: req, owner, zsets, stream, PEL
        for(ReserveRequest r:Arrays.asList(done,inFlight,queued)){Map<String,Object> again=accept(r);assertTrue("PROCESSING".equals(again.get("state"))||Boolean.TRUE.equals(again.get("reservationSuccess")),String.valueOf(again));} // client retries
        drain();
        assertEquals("RESERVED",job(done).get("state"));assertEquals("RESERVED",job(queued).get("state"));
        assertEquals("PROCESSING",job(inFlight).get("state"),"live MySQL lease: nobody else may process it yet");
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE request_id=?",new Timestamp(System.currentTimeMillis()-120000),inFlight.getRequestId());
        worker.redriveOrphan(job(inFlight).get("id").toString()); // no message survived for its first start: orphan path
        drain();
        assertEquals("RESERVED",job(inFlight).get("state"));
        for(ReserveRequest r:Arrays.asList(done,inFlight,queued))assertEquals(1,orders(r));
        assertEquals(97,stock(s));assertEquals(97,redisStock(s));assertSettledStream();
    }
    @Test void protocolThreeDispatchNeverSeesProtocolFourWork()throws Exception{
        Sale s=sale();ReserveRequest r=request(s);accept(r);V3Redis.Message m=message(r);
        assertEquals(V3JobStarter.Kind.OWNED,starter.begin(m,"t").kind);
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE request_id=?",new Timestamp(System.currentTimeMillis()-60000),r.getRequestId());
        assertNull(attempts.claim(),"protocol-3 claim ignores protocol-4 rows, even with an expired lease");
        assertEquals(0,attempts.availableHint(16));
        service.dispatch(); // protocol-3 dispatcher is inert in V3 mode
        assertEquals("PROCESSING",job(r).get("state"));
    }
    static MapRecord<String,Object,Object> record(V3Redis.Message m){
        Map<Object,Object> f=new LinkedHashMap<>();f.put("v","1");f.put("requestId",m.requestId);f.put("attemptId",m.attemptId);f.put("reservationId",m.reservationId);f.put("orderId",m.orderId);
        f.put("userId",m.userId);f.put("sessionId",m.sessionId);f.put("skuId",m.skuId);f.put("quantity",Integer.toString(m.quantity));f.put("deviceId",m.deviceId);f.put("ip",m.ip);
        f.put("fingerprint",m.fingerprint);f.put("saleVersion",Long.toString(m.saleVersion));f.put("deadline",Long.toString(m.deadline));f.put("acceptedAt",Long.toString(m.acceptedAt));f.put("traceId",m.traceId);
        return org.springframework.data.redis.connection.stream.StreamRecords.newRecord().in(V3Redis.STREAM).withId(m.streamId).ofMap(f);
    }
}
