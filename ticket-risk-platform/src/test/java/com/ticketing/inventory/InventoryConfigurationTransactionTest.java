package com.ticketing.inventory;
import com.ticketing.repository.*;
import com.ticketing.ticket.TicketSku;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.springframework.data.redis.core.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryConfigurationTransactionTest {
    StringRedisTemplate redis; TicketInventoryRepository skus; ReservationIntentRepository intents;
    InventoryService service; TransactionTemplate tx; TicketSku sku;
    Map<String,String> stock; AtomicBoolean configured; HashOperations<String,Object,Object> hash; ValueOperations<String,String> values;
    @BeforeEach void setup(){
        DataSourceTransactionManager manager=new DataSourceTransactionManager(new DriverManagerDataSource(
                "jdbc:h2:mem:config-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        tx=new TransactionTemplate(manager);redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS);
        skus=mock(TicketInventoryRepository.class);intents=mock(ReservationIntentRepository.class);
        hash=redis.opsForHash();values=redis.opsForValue();
        sku=new TicketSku();sku.setId("s");sku.setStatus("ON_SALE");sku.setTotalStock(20);sku.setRemainingStock(20);
        sku.setPurchaseLimit(2);sku.setSaleStartTime(new Timestamp(0));sku.setSaleEndTime(new Timestamp(Long.MAX_VALUE));
        when(skus.findByIdForUpdate("s")).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return sku;});
        stock=new ConcurrentHashMap<>();configured=new AtomicBoolean();
        when(redis.opsForValue().setIfAbsent(anyString(),anyString())).thenAnswer(call->
                stock.putIfAbsent(call.getArgument(0),call.getArgument(1))==null);
        doAnswer(call->{configured.set(true);return null;}).when(hash).putAll(anyString(),anyMap());
        when(redis.hasKey(anyString())).thenAnswer(call->call.getArgument(0).equals("ticket:{s}:config")?configured.get():stock.containsKey(call.getArgument(0)));
        service=new InventoryService(redis,skus,intents,new SimpleMeterRegistry(),manager);
    }
    @Test void coldPathHasRealTransactionAndDoesNotOverwriteExistingStock(){
        stock.put("ticket:{s}:stock","7");service.ensureReady("s");
        assertEquals("7",stock.get("ticket:{s}:stock"));assertTrue(configured.get());
        verify(redis.opsForValue(),never()).set(anyString(),anyString());
    }
    @Test void rollbackNeverPublishesConfigurationOrInitializesStock(){
        tx.executeWithoutResult(status->{service.configure("s");assertFalse(configured.get());status.setRollbackOnly();});
        assertFalse(configured.get());assertTrue(stock.isEmpty());
    }
    @Test void commitPublishesLatestRatherThanCapturedSaleStatus(){
        tx.executeWithoutResult(status->{service.configure("s");assertFalse(configured.get());sku.setStatus("OFF_SALE");});
        verify(redis.opsForHash()).putAll(eq("ticket:{s}:config"),argThat(map->"OFF_SALE".equals(map.get("status"))));
    }
    @Test void tradedMissingStockFailsClosedWithoutRefill(){
        when(intents.countBySkuId("s")).thenReturn(1L);
        assertThrows(com.ticketing.common.BusinessException.class,()->service.ensureReady("s"));
        assertTrue(stock.isEmpty());verify(redis.opsForValue(),never()).setIfAbsent(anyString(),anyString());
    }
    @Test void concurrentInstancesNeverReplaceRemainingStock() throws Exception{
        stock.put("ticket:{s}:stock","7");
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            List<Callable<Void>> jobs=new ArrayList<>();
            InventoryService other=new InventoryService(redis,skus,intents,new SimpleMeterRegistry(),tx.getTransactionManager());
            for(int i=0;i<20;i++){InventoryService instance=i%2==0?service:other;jobs.add(()->{instance.configure("s");return null;});}
            for(Future<Void> result:pool.invokeAll(jobs)) result.get();
        } finally{pool.shutdownNow();}
        assertEquals("7",stock.get("ticket:{s}:stock"));
    }
    @Test void redisPublicationFailureDoesNotReportCommittedDatabaseAsRolledBack(){
        doThrow(new IllegalStateException("redis down")).when(hash).putAll(anyString(),anyMap());
        assertDoesNotThrow(()->service.configure("s"));
        assertThrows(com.ticketing.common.BusinessException.class,()->service.ensureReady("s"));
    }
}
