package com.ticketing.payment;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.jdbc.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayPaymentServiceTest {
    ReservationPaymentPolicyTest fixture;Db db;PaymentGateway gateway;GatewayPaymentService service;
    @BeforeEach void setup(){
        fixture=new ReservationPaymentPolicyTest();fixture.setup();db=fixture.db;gateway=mock(PaymentGateway.class);
        service=new GatewayPaymentService(db,fixture.orders,new OrderStateMachine(new JdbcOrderRepository(db)),fixture.policy,
                new JdbcPaymentRepository(db),new JdbcIssuedTicketRepository(db),new OutboxService(db,new JdbcOutboxRepository(db)),gateway,null,new SimpleMeterRegistry(),fixture.tx.getTransactionManager());
        when(gateway.create(anyString(),any(),anyString(),anyLong())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return observation(call.getArgument(0),PaymentGateway.Status.PENDING,System.currentTimeMillis());});
    }
    PaymentGateway.Observation observation(String id,PaymentGateway.Status status,long at){
        PaymentGateway.Observation r=new PaymentGateway.Observation();r.setMerchantPaymentId(id);r.setStatus(status);r.setAmount(new BigDecimal("10"));r.setCurrency("CNY");r.setProviderTransactionId("provider-"+id);r.setPaidAt(at);return r;
    }
    String begin(){Map<String,Object> value=service.begin("order",2,fixture.request());assertEquals("UNKNOWN",value.get("status"));return value.get("paymentAttemptId").toString();}
    String order(){return fixture.orders.find("order").getStatus();}
    int stock(){return db.jdbc.queryForObject("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='sku-1'",Integer.class);}
    void expire(){Timestamp past=new Timestamp(System.currentTimeMillis()-1000);db.jdbc.update("UPDATE tb_ticket_order SET expires_at=? WHERE id='order'",past);db.jdbc.update("UPDATE tb_gateway_payment_attempt SET expires_at=?",past);}
    @Test void unknownRetainsInventoryAndCannotStartWalletOrAnotherAttempt(){
        String id=begin();assertEquals("PAYING",order());assertEquals(999,stock());
        assertThrows(BusinessException.class,()->fixture.tx.execute(s->fixture.wallet.pay("order",2,fixture.request(),"ip")));
        expire();fixture.tx.executeWithoutResult(s->fixture.orders.expire("order"));assertEquals("PAYING",order());
        when(gateway.close(id)).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return observation(id,PaymentGateway.Status.PENDING,System.currentTimeMillis());});
        service.drive(id);assertEquals("PAYING",order());assertEquals(999,stock());
    }
    @Test void validPaymentBeforeDeadlineCanBeConfirmedLateAndDuplicatedCallbackIssuesOnce(){
        String id=begin();long paid=System.currentTimeMillis()-2000;expire();
        PaymentGateway.Observation success=observation(id,PaymentGateway.Status.SUCCESS,paid);
        service.accept(id,success,null);service.accept(id,success,null);
        assertEquals("PAID",order());assertEquals(999,stock());
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE type='PAYMENT' AND status='SUCCESS'",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='ISSUE:order'",Integer.class));
        assertEquals(10000,db.jdbc.queryForObject("SELECT available_balance FROM tb_wallet_account WHERE user_id=2",Integer.class));
    }
    @Test void confirmedCloseReleasesOnceAndLateCaptureIsRefundedWithoutResurrectingOrder(){
        String id=begin();expire();when(gateway.close(id)).thenReturn(observation(id,PaymentGateway.Status.CLOSED,System.currentTimeMillis()));
        service.drive(id);service.accept(id,observation(id,PaymentGateway.Status.CLOSED,System.currentTimeMillis()),null);
        assertEquals("PAY_TIMEOUT",order());assertEquals(1000,stock());
        service.accept(id,observation(id,PaymentGateway.Status.SUCCESS,System.currentTimeMillis()-3000),null);
        assertEquals("REFUND_PENDING",service.view(id,2).get("status"));assertEquals("PAY_TIMEOUT",order());assertEquals(1000,stock());
        when(gateway.refund(eq(id),eq("REFUND:"+id),any(),eq("CNY"))).thenReturn(observation(id,PaymentGateway.Status.REFUNDED,System.currentTimeMillis()));
        service.drive(id);service.accept(id,observation(id,PaymentGateway.Status.REFUNDED,System.currentTimeMillis()),null);
        assertEquals("REFUNDED",service.view(id,2).get("status"));assertEquals(1000,stock());
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE type='REFUND'",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='ISSUE:order'",Integer.class));
    }
    @Test void oldAttemptLateCaptureDoesNotCloseCurrentWalletPayment(){
        String id=begin();service.accept(id,observation(id,PaymentGateway.Status.FAILED,System.currentTimeMillis()),null);
        assertEquals("WAITING_PAYMENT",order());PaymentRequest retry=fixture.request();retry.setPaymentRequestId("wallet-retry");
        fixture.tx.execute(s->fixture.wallet.pay("order",2,retry,"ip"));assertEquals("PAID",order());
        service.accept(id,observation(id,PaymentGateway.Status.SUCCESS,System.currentTimeMillis()),null);
        assertEquals("PAID",order());assertEquals(999,stock());assertEquals("REFUND_PENDING",service.view(id,2).get("status"));
        when(gateway.refund(eq(id),eq("REFUND:"+id),any(),eq("CNY"))).thenReturn(observation(id,PaymentGateway.Status.REFUNDED,System.currentTimeMillis()));
        service.drive(id);assertEquals("PAID",order());assertEquals(999,stock());
    }
    @Test void malformedMoneyOrProviderIdentityCannotCompletePayment(){
        String id=begin();PaymentGateway.Observation wrong=observation(id,PaymentGateway.Status.SUCCESS,System.currentTimeMillis());wrong.setAmount(new BigDecimal("11"));
        assertThrows(BusinessException.class,()->service.accept(id,wrong,null));assertEquals("PAYING",order());
        wrong.setAmount(new BigDecimal("10"));wrong.setMerchantPaymentId("another");
        assertThrows(BusinessException.class,()->service.accept(id,wrong,null));assertEquals("PAYING",order());
    }
    @Test void refundFailureRemainsDurableAndSuccessfulRetryReleasesOnce(){
        String id=begin();service.accept(id,observation(id,PaymentGateway.Status.SUCCESS,System.currentTimeMillis()),null);
        PaymentRequest refund=fixture.request();refund.setPaymentRequestId("refund-request");
        when(gateway.refund(eq(id),eq("REFUND:"+id),any(),eq("CNY"))).thenThrow(new IllegalStateException("network unavailable"));
        assertEquals("REFUND_PENDING",service.refund("order",2,refund).get("status"));assertEquals("REFUNDING",order());assertEquals(999,stock());
        when(gateway.refund(eq(id),eq("REFUND:"+id),any(),eq("CNY"))).thenReturn(observation(id,PaymentGateway.Status.REFUNDED,System.currentTimeMillis()));
        service.drive(id);service.refund("order",2,refund);
        assertEquals("REFUNDED",order());assertEquals(1000,stock());
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key='RELEASE:order'",Integer.class));
    }
}
