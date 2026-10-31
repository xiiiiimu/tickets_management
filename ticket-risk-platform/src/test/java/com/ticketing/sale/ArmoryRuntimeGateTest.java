package com.ticketing.sale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.*;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import com.ticketing.ticket.TicketSku;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArmoryRuntimeGateTest {
    Db db; TicketSaleArmory armory; TicketInventoryRepository skus; StringRedisTemplate redis;
    TransactionTemplate tx; String bytes; List<String> pointers; Map<Object,Object> config; TicketSku sku;
    @BeforeEach void setup(){
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:gate-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        db=new Db(new JdbcTemplate(source),new ObjectMapper());
        db.jdbc.execute("CREATE TABLE tb_event(id VARCHAR PRIMARY KEY,status VARCHAR)");
        db.jdbc.execute("CREATE TABLE tb_event_session(id VARCHAR PRIMARY KEY,event_id VARCHAR)");
        db.jdbc.execute("CREATE TABLE tb_ticket_sale_assembly(session_id VARCHAR PRIMARY KEY,state VARCHAR,version BIGINT,active_version BIGINT,active_digest VARCHAR)");
        db.jdbc.update("INSERT INTO tb_event VALUES('event','ON_SALE')");db.jdbc.update("INSERT INTO tb_event_session VALUES('session','event')");
        long start=System.currentTimeMillis()-3600000,end=System.currentTimeMillis()+3600000;
        bytes="{\"sessionId\":\"session\",\"version\":1,\"bucketCount\":16,\"skus\":[{\"id\":\"sku\",\"price\":\"10\",\"totalStock\":1000,\"purchaseLimit\":2,\"saleStart\":"+start+",\"saleEnd\":"+end+"}],\"rules\":[]}";
        db.jdbc.update("INSERT INTO tb_ticket_sale_assembly VALUES('session','READY',1,1,?)",new SaleManifest(bytes,new ObjectMapper()).digest());
        redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS);skus=mock(TicketInventoryRepository.class);
        StockBucketService buckets=mock(StockBucketService.class);when(buckets.bucketCount()).thenReturn(16);when(buckets.active("sku")).thenReturn(true);
        pointers=new ArrayList<>(Arrays.asList("1","1",bytes));
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->pointers);
        when(redis.countExistingKeys(anyCollection())).thenReturn(18L);
        when(redis.opsForValue().get("ticket:{sku}:assembly-fence")).thenReturn("1");
        config=new HashMap<>();config.put("status","ON_SALE");config.put("assemblyVersion","1");config.put("limit","2");config.put("start",Long.toString(start));config.put("end",Long.toString(end));
        when(redis.opsForHash().entries("ticket:{sku}:config")).thenAnswer(call->config);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        armory=new TicketSaleArmory(db,redis,skus,buckets,mock(InventoryService.class),mock(RiskDecisionService.class),mock(RiskRuleEvaluatorRegistry.class),new ObjectMapper(),new SimpleMeterRegistry(),manager);
        sku=new TicketSku();sku.setId("sku");sku.setEventSessionId("session");sku.setStatus("ON_SALE");sku.setPrice(new BigDecimal("10"));sku.setTotalStock(1000);sku.setPurchaseLimit(2);sku.setSaleStartTime(new Timestamp(start));sku.setSaleEndTime(new Timestamp(end));
        when(skus.findStaticById("sku")).thenReturn(sku);
    }
    @Test void entryDoesNotReadSkuOrSumAndFinalTransactionReusesOneStaticSku(){
        assertEquals(1,armory.runtimePlan("session","sku").version());verifyNoInteractions(skus);
        assertSame(sku,tx.execute(s->armory.runtimeSku("session","sku",1)));
        verify(skus).findStaticById("sku");verify(skus,never()).findById(any());
    }
    @Test void finalGateRequiresCallerTransactionAndRejectsOldQueuedVersion(){
        assertThrows(IllegalStateException.class,()->armory.runtimeSku("session","sku",1));
        assertThrows(BusinessException.class,()->tx.execute(s->armory.runtimeSku("session","sku",0)));
    }
    @Test void cachedManifestCannotHideDeletionOrTampering(){
        armory.runtimePlan("session","sku");pointers.set(2,null);
        assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
        pointers.set(2,bytes+" ");assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
    }
    @Test void recoveryUnpublishedFenceAndMissingBucketFailClosed(){
        db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state='RECOVERING'");
        assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
        db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state='READY'");pointers.set(1,"2");
        assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
        pointers.set(1,"1");when(redis.countExistingKeys(anyCollection())).thenReturn(17L);
        assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
    }
    @Test void statusAndStaticPriceChangesCannotCrossFinalGate(){
        config.put("status","OFF_SALE");assertThrows(BusinessException.class,()->armory.runtimePlan("session","sku"));
        config.put("status","ON_SALE");sku.setPrice(new BigDecimal("20"));
        assertThrows(BusinessException.class,()->tx.execute(s->armory.runtimeSku("session","sku",1)));
    }
    void optimized(){
        ReflectionTestUtils.setField(armory,"planSingleFlight",true);ReflectionTestUtils.setField(armory,"planDetachedRedis",true);
        ReflectionTestUtils.setField(armory,"planLoadWaitMillis",10000L);
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Redis must be outside the short DB transactions");
            return ((Collection<?>)call.getArgument(0)).size()==2?new ArrayList<>(pointers.subList(0,2)):new ArrayList<>(pointers);
        });
        when(redis.countExistingKeys(anyCollection())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return 18L;});
        when(redis.opsForValue().get("ticket:{sku}:assembly-fence")).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return "1";});
        when(redis.opsForHash().entries("ticket:{sku}:config")).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return config;});
    }
    @Test void detachedLoadHasNoRedisInsideTransactionsAndOnlyTwoDatabaseReads(){
        optimized();Db observed=spy(db);ReflectionTestUtils.setField(armory,"db",observed);
        assertEquals(1,armory.cachedRuntimePlan("session","sku").version());assertEquals(1,armory.cachedRuntimePlan("session","sku").version());
        verify(observed,times(2)).one(contains("JOIN tb_event_session"),eq("session"));
    }
    @Test void statusChangeDuringRedisValidationFailsDatabaseRecheckAndCanRecover(){
        optimized();when(redis.countExistingKeys(anyCollection())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());db.jdbc.update("UPDATE tb_event SET status='OFF_SALE'");return 18L;});
        assertEquals("场次未就绪",assertThrows(BusinessException.class,()->armory.cachedRuntimePlan("session","sku")).getMessage());
        doReturn(18L).when(redis).countExistingKeys(anyCollection());db.jdbc.update("UPDATE tb_event SET status='ON_SALE'");
        assertEquals(1,armory.cachedRuntimePlan("session","sku").version());
    }
    @Test void invalidationDuringLoadCannotRepublishItsOldResult(){
        optimized();when(redis.countExistingKeys(anyCollection())).thenAnswer(call->{armory.invalidatePlans();return 18L;});
        assertThrows(com.ticketing.ticket.AttemptAdmissionException.class,()->armory.cachedRuntimePlan("session","sku"));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(armory,"planCache")).isEmpty());
        when(redis.countExistingKeys(anyCollection())).thenReturn(18L);assertEquals(1,armory.cachedRuntimePlan("session","sku").version());
    }
    @Test void versionChangeBetweenSnapshotAndRecheckCannotReturnAnOldPlan(){
        optimized();when(redis.countExistingKeys(anyCollection())).thenAnswer(call->{db.jdbc.update("UPDATE tb_ticket_sale_assembly SET version=2,active_version=2");return 18L;});
        assertEquals("销售计划在校验期间发生变化",assertThrows(BusinessException.class,()->armory.cachedRuntimePlan("session","sku")).getMessage());
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(armory,"planCache")).isEmpty());
    }
    @Test void cacheExpiryDuringRedisRoundTripCannotBeReturned(){
        optimized();AtomicLong clock=new AtomicLong(System.currentTimeMillis());ReflectionTestUtils.setField(armory,"planClock",(java.util.function.LongSupplier)clock::get);
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{if(((Collection<?>)call.getArgument(0)).size()==2){clock.addAndGet(501);return new ArrayList<>(pointers.subList(0,2));}return new ArrayList<>(pointers);});
        assertThrows(com.ticketing.ticket.AttemptAdmissionException.class,()->armory.cachedRuntimePlan("session","sku"));assertTrue(((Map<?,?>)ReflectionTestUtils.getField(armory,"planCache")).isEmpty());
    }
    @Test void saleEndingDuringRedisRoundTripCannotBeReturned(){
        optimized();AtomicLong clock=new AtomicLong(System.currentTimeMillis());ReflectionTestUtils.setField(armory,"planClock",(java.util.function.LongSupplier)clock::get);
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{if(((Collection<?>)call.getArgument(0)).size()==2){clock.set(Long.parseLong(config.get("end").toString()));return new ArrayList<>(pointers.subList(0,2));}return new ArrayList<>(pointers);});
        assertThrows(BusinessException.class,()->armory.cachedRuntimePlan("session","sku"));
    }
    @Test void sameKeyConcurrentCallsShareOneActualDatabaseLoad()throws Exception{
        optimized();io.micrometer.core.instrument.MeterRegistry registry=(io.micrometer.core.instrument.MeterRegistry)ReflectionTestUtils.getField(armory,"metrics");
        Db observed=spy(db);ReflectionTestUtils.setField(armory,"db",observed);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(8);
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if(((Collection<?>)call.getArgument(0)).size()==2)return new ArrayList<>(pointers.subList(0,2));
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new ArrayList<>(pointers);
        });
        try{
            Future<SaleManifest> leader=pool.submit(()->armory.cachedRuntimePlan("session","sku"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            List<Future<SaleManifest>> followers=new ArrayList<>();for(int i=0;i<7;i++)followers.add(pool.submit(()->armory.cachedRuntimePlan("session","sku")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(registry.counter("sale.plan.merged").count()<7&&System.nanoTime()<deadline)Thread.yield();
            assertEquals(7,registry.counter("sale.plan.merged").count());release.countDown();assertEquals(1,leader.get(5,TimeUnit.SECONDS).version());
            for(Future<SaleManifest> f:followers)assertEquals(1,f.get(5,TimeUnit.SECONDS).version());assertEquals(1,registry.counter("sale.plan.db.load").count());
            verify(observed,times(2)).one(contains("JOIN tb_event_session"),eq("session"));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void withoutSingleFlightEightColdCallersPerformEightRealDatabaseLoads()throws Exception{
        optimized();ReflectionTestUtils.setField(armory,"planSingleFlight",false);
        Db observed=spy(db);ReflectionTestUtils.setField(armory,"db",observed);
        io.micrometer.core.instrument.MeterRegistry registry=(io.micrometer.core.instrument.MeterRegistry)ReflectionTestUtils.getField(armory,"metrics");
        CountDownLatch entered=new CountDownLatch(8),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(8);
        when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if(((Collection<?>)call.getArgument(0)).size()==2)return new ArrayList<>(pointers.subList(0,2));
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new ArrayList<>(pointers);
        });
        try{
            List<Future<SaleManifest>> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(pool.submit(()->armory.cachedRuntimePlan("session","sku")));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(8,registry.counter("sale.plan.db.load").count());release.countDown();
            for(Future<SaleManifest> f:requests)assertEquals(1,f.get(5,TimeUnit.SECONDS).version());
            verify(observed,times(16)).one(contains("JOIN tb_event_session"),eq("session"));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void allFlagCombinationsKeepTheAuthoritativeGateInsideItsTransaction(){
        for(boolean flight:new boolean[]{false,true})for(boolean detached:new boolean[]{false,true}){
            setup();ReflectionTestUtils.setField(armory,"planSingleFlight",flight);ReflectionTestUtils.setField(armory,"planDetachedRedis",detached);
            List<Boolean> contexts=new ArrayList<>();
            when(redis.opsForValue().multiGet(anyCollection())).thenAnswer(call->{contexts.add(TransactionSynchronizationManager.isActualTransactionActive());return ((Collection<?>)call.getArgument(0)).size()==2?new ArrayList<>(pointers.subList(0,2)):new ArrayList<>(pointers);});
            assertEquals(1,armory.cachedRuntimePlan("session","sku").version());
            if(detached)assertFalse(contexts.contains(true));else assertTrue(contexts.contains(true));
            contexts.clear();assertSame(sku,tx.execute(status->armory.runtimeSku("session","sku",1)));
            assertEquals(Collections.singletonList(true),contexts,"authoritative gate still protects its Redis/SQL checks under every flag combination");
        }
    }
    @Test void prometheusAcceptsIdenticalTagSchemasForSuccessfulAndFailedLoads(){
        io.micrometer.prometheus.PrometheusMeterRegistry registry=new io.micrometer.prometheus.PrometheusMeterRegistry(io.micrometer.prometheus.PrometheusConfig.DEFAULT);
        ReflectionTestUtils.setField(armory,"metrics",registry);ReflectionTestUtils.setField(armory,"planLoads",new BoundedPlanLoads<>(registry,64,256));
        optimized();assertEquals(1,armory.cachedRuntimePlan("session","sku").version());
        armory.invalidatePlans();db.jdbc.update("UPDATE tb_event SET status='OFF_SALE'");
        assertThrows(BusinessException.class,()->armory.cachedRuntimePlan("session","sku"));
        assertTrue(registry.scrape().contains("sale_plan_db_duration_seconds_count"));
        assertEquals(1,registry.get("sale.plan.db.duration").tags("read","snapshot","phase","body","outcome","failure").timer().count());
        registry.close();
    }
}
