package com.ticketing.payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.StockBucketService;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.jdbc.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationPaymentPolicyTest {
    Db db;TransactionTemplate tx;WalletService wallet;OrderService orders;ReservationPaymentPolicy policy;
    JdbcWalletRepository accounts;AutoPaymentExecutor auto;RiskDecisionService risk;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:v2pay-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("data.sql")).execute(ds);
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        JdbcOrderRepository orderRepo=new JdbcOrderRepository(db);OrderStateMachine machine=new OrderStateMachine(orderRepo);
        JdbcTicketInventoryRepository inventory=new JdbcTicketInventoryRepository(db);StockBucketService buckets=new StockBucketService(db,16);
        ReflectionTestUtils.setField(inventory,"buckets",buckets);tx.executeWithoutResult(s->{
            for(int i=0;i<16;i++){int count=i<8?63:62;db.jdbc.update("INSERT INTO tb_ticket_stock_bucket(sku_id,bucket_id,total_stock,remaining_stock) VALUES('sku-1',?,?,?)",i,count,count);}
            buckets.configure(inventory.findStaticById("sku-1"));
        });
        OutboxService outbox=new OutboxService(db,new JdbcOutboxRepository(db));SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        WorkflowExecutionService workflow=mock(WorkflowExecutionService.class);
        orders=new OrderService(orderRepo,new JdbcReservationRepository(db),inventory,new JdbcUserPurchaseRepository(db),machine,outbox,workflow,metrics);
        accounts=spy(new JdbcWalletRepository(db));risk=mock(RiskDecisionService.class);policy=new ReservationPaymentPolicy(db);
        wallet=new WalletService(accounts,new JdbcPaymentRepository(db),new JdbcWalletLedgerRepository(db),new JdbcIssuedTicketRepository(db),orders,machine,risk,outbox,workflow,metrics);
        ReflectionTestUtils.setField(wallet,"reservationPolicy",policy);
        auto=new AutoPaymentExecutor(orders,machine,new JdbcPaymentRepository(db),outbox,workflow,metrics);ReflectionTestUtils.setField(auto,"reservationPolicy",policy);
        tx.executeWithoutResult(s->{
            Timestamp expiry=new Timestamp(System.currentTimeMillis()+600000);
            db.jdbc.update("INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,quantity,device_id,ip,status) VALUES('req','fp','res','order',2,'sku-1',1,'device','ip','COMPLETED')");
            new JdbcReservationRepository(db).insertHeld("res","req",2,"sku-1",1,expiry);
            orderRepo.insertWaitingPayment("order","res",2,"sku-1",1,new BigDecimal("10"),"device","ip","trace",expiry);
            db.jdbc.update("INSERT INTO tb_user_purchase VALUES(2,'sku-1',1)");
            db.jdbc.update("UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock-1 WHERE sku_id='sku-1' AND bucket_id=0");
            db.jdbc.update("INSERT INTO tb_stock_bucket_allocation(reservation_id,request_id,sku_id,bucket_id,quantity,released) VALUES('res','req','sku-1',0,1,false)");
            db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,queue_expires_at,reservation_id,order_id) VALUES('job','req','fp',2,'session-1','sku-1',1,'device','ip',1,'RESERVED','REVIEW',?,'res','order')",expiry);
        });
    }
    PaymentRequest request(){PaymentRequest p=new PaymentRequest();p.setPaymentRequestId("pay-request");p.setDeviceId("device");return p;}
    @Test void reviewQualificationSurvivesNewBlockAndDuplicatePaymentDoesNotRerunRisk(){
        db.jdbc.update("INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,expires_at) VALUES('block','USER','2','HIGH','BLOCK',100,?)",new Timestamp(System.currentTimeMillis()+60000));
        Map<String,Object> one=tx.execute(s->wallet.pay("order",2,request(),"new-ip"));Map<String,Object> two=tx.execute(s->wallet.pay("order",2,request(),"new-ip"));
        assertEquals("SUCCESS",one.get("status"));assertEquals(one.get("id"),two.get("id"));verifyNoInteractions(risk);
        assertEquals(9990,db.jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account WHERE user_id=2",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='ISSUE:order'",Integer.class));
    }
    @Test void defaultMockCannotPayNewOrdersButExplicitTestConfigurationCan(){
        assertNull(tx.execute(s->auto.execute("order")));assertEquals("WAITING_PAYMENT",orders.find("order").getStatus());
        ReflectionTestUtils.setField(policy,"mockEnabled",true);assertEquals("SUCCESS",tx.execute(s->auto.execute("order")).get("status"));
        assertEquals(10000,db.jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account WHERE user_id=2",Integer.class));
    }
    @Test void unknownPayingNeverExpiresOrReleasesBlindly(){
        db.jdbc.update("UPDATE tb_ticket_order SET status='PAYING',expires_at=? WHERE id='order'",new Timestamp(System.currentTimeMillis()-1));
        tx.executeWithoutResult(s->orders.expire("order"));assertEquals("PAYING",orders.find("order").getStatus());
        assertEquals(999,db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='sku-1'",Integer.class));
        assertEquals("HELD",db.one("SELECT status FROM tb_reservation WHERE id='res'").get("status"));
    }
    @Test void paymentCrossingDeadlineRollsBackDebitThenTimeoutReleasesExactlyOnce()throws Exception{
        long deadline=System.currentTimeMillis()+1000;db.jdbc.update("UPDATE tb_ticket_order SET expires_at=? WHERE id='order'",new Timestamp(deadline));
        CountDownLatch debited=new CountDownLatch(1),finish=new CountDownLatch(1);
        doAnswer(call->{Object result=call.callRealMethod();debited.countDown();if(!finish.await(4,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return result;}).when(accounts).tryDebit(anyLong(),any());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> pay=pool.submit(()->tx.execute(s->wallet.pay("order",2,request(),"ip")));assertTrue(debited.await(3,TimeUnit.SECONDS));
            long wait=deadline-System.currentTimeMillis()+30;if(wait>0)Thread.sleep(wait);
            Future<?> expire=pool.submit(()->tx.executeWithoutResult(s->orders.expire("order")));finish.countDown();
            assertThrows(ExecutionException.class,()->pay.get(4,TimeUnit.SECONDS));expire.get(4,TimeUnit.SECONDS);
            tx.executeWithoutResult(s->orders.expire("order"));
            assertEquals("PAY_TIMEOUT",orders.find("order").getStatus());assertEquals(10000,db.jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account WHERE user_id=2",Integer.class));
            assertEquals(1000,db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='sku-1'",Integer.class));
            assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RELEASE:order'",Integer.class));
        }finally{finish.countDown();pool.shutdownNow();}
    }
}
