package com.ticketing.integration;

import com.ticketing.ticket.*;
import com.ticketing.order.*;
import com.ticketing.risk.RiskProfile;
import com.ticketing.repository.WalletRepository;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local wallet/issued rows only. No external payment, MQ, notification provider or Agent. */
class VectorFulfillmentIT extends VectorReservationIT {
    @SpyBean OrderService measuredOrders;
    @SpyBean WalletRepository measuredWallet;
    @Test void localPaymentExpiryRaceThenRefundRestoresEveryBucketOnce()throws Exception{
        isolated();Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);
        new TransactionTemplate(manager).executeWithoutResult(status->{RiskProfile p=new RiskProfile();p.setSubjectType("USER");p.setSubjectValue(r.getUserId().toString());p.setDecision("PASS");p.setRiskLevel("LOW");p.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));assertTrue(profiles.upsert(p));});
        Map<String,Object> held=run(r);assertEquals("RESERVED",held.get("state"));String order=held.get("order_id").toString();CountDownLatch locked=new CountDownLatch(1),pay=new CountDownLatch(1),expiryEntered=new CountDownLatch(1);ExecutorService threads=Executors.newFixedThreadPool(2);
        doAnswer(call->{locked.countDown();assertTrue(pay.await(4,TimeUnit.SECONDS));return call.callRealMethod();}).when(measuredWallet).tryDebit(eq(r.getUserId().longValue()),any());
        doAnswer(call->{boolean expiring=Thread.currentThread().getName().equals("vector-test-expire");if(expiring)expiryEntered.countDown();TicketOrder value=(TicketOrder)call.callRealMethod();if(expiring)value.setExpiresAt(new Timestamp(0));return value;}).when(measuredOrders).lock(order);
        try{Future<?> payment=threads.submit(()->wallet.pay(order,r.getUserId(),payRequest(r),"test"));assertTrue(locked.await(4,TimeUnit.SECONDS));Future<?> expiry=threads.submit(()->{Thread.currentThread().setName("vector-test-expire");orderService.expire(order);});assertTrue(expiryEntered.await(4,TimeUnit.SECONDS));pay.countDown();payment.get(6,TimeUnit.SECONDS);expiry.get(6,TimeUnit.SECONDS);}finally{pay.countDown();threads.shutdownNow();}
        assertEquals("PAID",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));assertEquals(11,stock(s));assertEquals(11,redisStock(s));consume("ISSUE",order);consume("ISSUE",order);assertEquals(5,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_issued_ticket WHERE order_id=?",Integer.class,order));
        com.ticketing.payment.PaymentRequest refund=payRequest(r);wallet.refund(order,r.getUserId(),refund);wallet.refund(order,r.getUserId(),refund);inventory.release(s.sku,r.getUserId(),held.get("reservation_id").toString());inventory.release(s.sku,r.getUserId(),held.get("reservation_id").toString());assertEquals(16,stock(s));assertEquals(16,redisStock(s));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_issued_ticket WHERE order_id=?",Integer.class,order));assertEquals("REFUNDED",db.one("SELECT status FROM tb_ticket_order WHERE id=?",order).get("status"));verifyNoInteractions(agent);
    }
}
