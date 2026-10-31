package com.ticketing.payment;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.TicketOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Qualification is frozen by the committed V2 reservation; subsequent portraits cannot revoke it. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationPaymentPolicy {
    private final Db db;
    @Value("${ticketing.reservations-v2.mock-payment.enabled:false}") private boolean mockEnabled;
    public ReservationPaymentPolicy(Db db){this.db=db;}
    public boolean isV2(String order){return db.one("SELECT id FROM tb_reservation_job WHERE order_id=?",order)!=null;}
    public boolean allowsMock(){return mockEnabled;}
    public void claim(TicketOrder order,PaymentRequest request,String operation){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Payment idempotency claim requires transaction");
        db.jdbc.update("INSERT INTO tb_payment_request_claim(payment_request_id,order_id,user_id,operation,device_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE payment_request_id=payment_request_id",
                request.getPaymentRequestId(),order.getId(),order.getUserId(),operation,request.getDeviceId());
        Map<String,Object> prior=db.one("SELECT * FROM tb_payment_request_claim WHERE payment_request_id=?",request.getPaymentRequestId());
        BusinessException.require(prior!=null&&order.getId().equals(prior.get("order_id"))&&Db.number(prior,"user_id")==order.getUserId()
                &&operation.equals(prior.get("operation"))&&request.getDeviceId().equals(prior.get("device_id")),"支付编号已用于其他交易或通道");
    }
    public void assertQualified(TicketOrder order){
        Map<String,Object> grant=db.one("SELECT j.user_id,j.decision,j.state,r.status reservation_status,i.status intent_status "
                +"FROM tb_reservation_job j JOIN tb_reservation r ON r.id=j.reservation_id "
                +"JOIN tb_reserve_request i ON i.order_id=j.order_id WHERE j.order_id=?",order.getId());
        BusinessException.require(grant!=null&&Db.number(grant,"user_id")==order.getUserId()
                &&Arrays.asList("PASS","REVIEW").contains(grant.get("decision"))&&"RESERVED".equals(grant.get("state"))
                &&"HELD".equals(grant.get("reservation_status"))&&"COMPLETED".equals(grant.get("intent_status")),"订单未获得有效支付资格");
    }
    public void startPaying(TicketOrder order){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Payment transition requires transaction");
        BusinessException.require(db.jdbc.update("UPDATE tb_ticket_order SET status='PAYING',version=version+1,updated_at=CURRENT_TIMESTAMP "
                +"WHERE id=? AND status='WAITING_PAYMENT' AND version=? AND expires_at>CURRENT_TIMESTAMP(3)",order.getId(),order.getVersion())==1,"订单已变更或支付已超时");
        order.setStatus("PAYING");order.setVersion(order.getVersion()+1);
    }
}
