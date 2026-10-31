package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real transaction split and per-SKU cursor, with explicit concurrency barriers. */
class ReservationQualificationOrderTest {
    ReservationAttemptTransactionTest f;ReservationQualificationOrder order;TransactionTemplate tx;
    @BeforeEach void setup(){
        f=new ReservationAttemptTransactionTest();f.setup();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql")).execute(f.db.jdbc.getDataSource());
        order=new ReservationQualificationOrder(f.db);tx=new TransactionTemplate(new DataSourceTransactionManager(f.db.jdbc.getDataSource()));
        ReflectionTestUtils.setField(f.service,"qualificationOrder",order);
        f.seed(31000,3);
        doAnswer(a->{ReservationIntent r=a.getArgument(0);r.setInventoryBucket(0);r.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return null;}).when(f.inventory).loadRuntimeHold(any(),anyLong());
    }
    Map<String,Object> second(String sku){
        f.db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,admission,lease_token,lease_until,queue_expires_at,reservation_id,order_id,attempt_protocol,waiting_deadline,processing_started_at) SELECT 'job2','r2','fp2',2,session_id,?,quantity,'device2','ip2',sale_version,state,decision,admission,'token2',lease_until,queue_expires_at,'res2','order2',attempt_protocol,waiting_deadline,processing_started_at FROM tb_reservation_job WHERE id='job'",sku);
        ReservationIntent r=new ReservationIntent();r.setRequestId("r2");r.setFingerprint("fp2");r.setReservationId("res2");r.setOrderId("order2");r.setUserId(2);r.setSkuId(sku);r.setQuantity(1);r.setDeviceId("device2");r.setIp("ip2");r.setStatus("REQUESTED");
        when(f.intents.findByRequestId("r2")).thenReturn(r);when(f.intents.findByRequestIdForUpdate("r2")).thenReturn(r);
        when(f.skus.tryDecreaseStockForReservation(r)).thenReturn(true);when(f.purchases.tryIncreaseWithinLimit(2L,sku,1,2)).thenReturn(true);
        when(f.inventory.reserveRuntime(eq(r),eq(1L),anyLong())).thenAnswer(a->{r.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return 100L;});return f.queue.get("job2");
    }
    Map<String,Object> fresh(String id){return f.queue.get(id);}
    long head(String sku){return f.db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE sku_id=? AND sale_version=1",Long.class,sku);}
    @Test void publicationIsDurableIdempotentAndRollbackLeavesNoHole(){
        tx.executeWithoutResult(s->{order.publish(fresh("job"));s.setRollbackOnly();});assertNull(fresh("job").get("qualification_seq"));
        assertEquals(0,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_sku_order",Integer.class));
        f.service.prepare(f.job);f.service.prepare(f.job);assertEquals(1,Db.number(fresh("job"),"qualification_seq"));
        assertEquals(1,f.db.jdbc.queryForObject("SELECT published_seq FROM tb_reservation_sku_order",Long.class));
    }
    @Test void unclassifiedOrBlockedTaskCannotPublishQualification(){
        f.db.jdbc.update("UPDATE tb_reservation_job SET admission=NULL WHERE id='job'");
        assertThrows(IllegalStateException.class,()->f.service.prepare(f.job));assertNull(fresh("job").get("qualification_seq"));
        f.db.jdbc.update("UPDATE tb_reservation_job SET admission='{}',decision='BLOCK' WHERE id='job'");
        assertThrows(IllegalStateException.class,()->f.service.prepare(f.job));assertEquals(0,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_sku_order",Integer.class));
    }
    @Test void reviewPublishedFirstWinsOverLaterPassAndLaterCannotCallLua(){
        Map<String,Object> two=second("sku");f.db.jdbc.update("UPDATE tb_reservation_job SET decision='REVIEW' WHERE id='job'");
        f.service.prepare(f.job);f.service.prepare(two);
        assertThrows(ReservationQualificationOrder.Pending.class,()->f.service.reserve(two));verify(f.inventory,never()).reserveRuntime(argThat(r->"r2".equals(r.getRequestId())),anyLong(),anyLong());
        f.service.reserve(f.job);f.service.reserve(two);assertEquals(3,head("sku"));assertEquals("RESERVED",fresh("job2").get("state"));
    }
    @Test void qualificationPublicationNotHttpOrClaimOrderDefinesSequence(){
        Map<String,Object> two=second("sku");f.service.prepare(two);f.service.prepare(f.job);
        assertEquals(1,Db.number(fresh("job2"),"qualification_seq"));assertEquals(2,Db.number(fresh("job"),"qualification_seq"));
        assertThrows(ReservationQualificationOrder.Pending.class,()->f.service.reserve(f.job));f.service.reserve(two);f.service.reserve(f.job);
    }
    @Test void secondLuaDoesNotWaitForFirstMysqlConfirmation()throws Exception{
        Map<String,Object> two=second("sku");f.service.prepare(f.job);f.service.prepare(two);
        CountDownLatch confirming=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        when(f.skus.tryDecreaseStockForReservation(f.intent)).thenAnswer(a->{confirming.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return true;});
        try{Future<?> first=pool.submit(()->f.service.reserve(f.job));assertTrue(confirming.await(5,TimeUnit.SECONDS));
            Future<?> next=pool.submit(()->f.service.reserve(two));next.get(5,TimeUnit.SECONDS);
            assertEquals("RESERVED",fresh("job2").get("state"));assertEquals("PROCESSING",fresh("job").get("state"));
            release.countDown();first.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void differentSkusCanRunLuaConcurrently()throws Exception{
        Map<String,Object> two=second("different-sku");f.service.prepare(f.job);f.service.prepare(two);
        CyclicBarrier both=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        when(f.inventory.reserveRuntime(any(),eq(1L),anyLong())).thenAnswer(a->{both.await(5,TimeUnit.SECONDS);ReservationIntent r=a.getArgument(0);r.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return 100L;});
        try{Future<?> a=pool.submit(()->f.service.reserve(f.job)),b=pool.submit(()->f.service.reserve(two));a.get(7,TimeUnit.SECONDS);b.get(7,TimeUnit.SECONDS);assertEquals(2,head("sku"));assertEquals(2,head("different-sku"));}
        finally{pool.shutdownNow();}
    }
    @Test void failedHeadMustBeTombstonedBeforeLaterLuaAndNoDoubleAdvance(){
        Map<String,Object> two=second("sku");f.service.prepare(f.job);f.service.prepare(two);
        when(f.inventory.reserveRuntime(eq(f.intent),eq(1L),anyLong())).thenReturn(-3L);
        assertThrows(ReservationV2Transactions.Rejected.class,()->f.service.reserve(f.job));assertEquals(1,head("sku"));
        assertThrows(ReservationQualificationOrder.Pending.class,()->f.service.reserve(two));
        f.service.abort(f.job,"SOLD_OUT");verify(f.inventory).abortRuntime(f.intent,1);assertEquals(2,head("sku"));
        f.service.abort(f.job,"SOLD_OUT");assertEquals(2,head("sku"));f.service.reserve(two);assertEquals(3,head("sku"));
    }
    @Test void abortFailureCannotSkipHeadOrMarkFinal(){
        f.service.prepare(f.job);doThrow(new IllegalStateException("Redis unavailable")).when(f.inventory).abortRuntime(f.intent,1);
        assertThrows(IllegalStateException.class,()->f.service.abort(f.job,"EXECUTION_UNAVAILABLE"));assertEquals(1,head("sku"));assertEquals("PROCESSING",fresh("job").get("state"));assertFalse(ReservationQualificationOrder.finished(fresh("job")));
    }
    @Test void mysqlConfirmationRollbackDoesNotUndoTurnButCompensatesExistingHold(){
        f.service.prepare(f.job);when(f.skus.tryDecreaseStockForReservation(f.intent)).thenReturn(false);
        assertThrows(ReservationV2Transactions.Rejected.class,()->f.service.reserve(f.job));assertEquals(2,head("sku"));assertTrue(ReservationQualificationOrder.finished(fresh("job")));
        f.service.abort(f.job,"MYSQL_STOCK_REJECTED");verify(f.inventory).abortRuntime(f.intent,1);assertEquals(2,head("sku"));assertEquals("FAILED",fresh("job").get("state"));
    }
    @Test void reclaimedTokenReusesSequenceAndOldWorkerCannotExecute(){
        f.service.prepare(f.job);f.db.jdbc.update("UPDATE tb_reservation_job SET lease_token='replacement' WHERE id='job'");f.service.reserve(f.job);verifyNoInteractions(f.inventory);
        Map<String,Object> replacement=fresh("job");f.service.prepare(replacement);f.service.reserve(replacement);assertEquals(1,Db.number(fresh("job"),"qualification_seq"));assertEquals(2,head("sku"));
    }
    @Test void restartAfterLuaTurnCommitReadsMarkerInsteadOfRepeatingReserve(){
        f.service.prepare(f.job);doThrow(new IllegalStateException("process interrupted before confirmation")).when(f.inventory).loadRuntimeHold(f.intent,1);
        assertThrows(IllegalStateException.class,()->f.service.reserve(f.job));assertTrue(ReservationQualificationOrder.finished(fresh("job")));
        doAnswer(a->{f.intent.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return null;}).when(f.inventory).loadRuntimeHold(f.intent,1);
        f.service.reserve(f.job);verify(f.inventory,times(1)).reserveRuntime(eq(f.intent),eq(1L),anyLong());assertEquals("RESERVED",fresh("job").get("state"));
    }
    @Test void recoveryCannotSkipPredecessor(){
        Map<String,Object> two=second("sku");f.service.prepare(f.job);f.service.prepare(two);
        assertThrows(ReservationQualificationOrder.Pending.class,()->f.service.abort(two,"RECOVERED_ABORT"));assertEquals("PROCESSING",fresh("job2").get("state"));assertEquals(1,head("sku"));
    }
    @Test void twoInstancesPublishContiguousUniqueSequences()throws Exception{
        Map<String,Object> two=second("sku");ReservationQualificationOrder other=new ReservationQualificationOrder(f.db);CyclicBarrier start=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<?> a=pool.submit(()->{await(start);tx.executeWithoutResult(s->order.publish(fresh("job")));});
            Future<?> b=pool.submit(()->{await(start);tx.executeWithoutResult(s->other.publish(two));});a.get(6,TimeUnit.SECONDS);b.get(6,TimeUnit.SECONDS);
            assertEquals(new HashSet<>(Arrays.asList(1L,2L)),new HashSet<>(f.db.jdbc.queryForList("SELECT qualification_seq FROM tb_reservation_job",Long.class)));}
        finally{pool.shutdownNow();}
    }
    private static void await(CyclicBarrier barrier){try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception failure){throw new RuntimeException(failure);}}
}
