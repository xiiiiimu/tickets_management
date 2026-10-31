package com.ticketing.payment;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.*;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/** Durable payment attempts; never holds a database connection across gateway HTTP. */
@Service
@ConditionalOnProperty(name="ticketing.payment.gateway.enabled",havingValue="true")
public class GatewayPaymentService {
    private final Db db;private final OrderService orders;private final OrderStateMachine machine;private final ReservationPaymentPolicy qualification;
    private final PaymentRepository payments;private final IssuedTicketRepository tickets;private final OutboxService outbox;
    private final PaymentGateway gateway;private final TaskPools pools;private final MeterRegistry metrics;private final TransactionTemplate tx;
    public GatewayPaymentService(Db db,OrderService orders,OrderStateMachine machine,ReservationPaymentPolicy qualification,
            PaymentRepository payments,IssuedTicketRepository tickets,OutboxService outbox,PaymentGateway gateway,TaskPools pools,
            MeterRegistry metrics,PlatformTransactionManager manager){
        this.db=db;this.orders=orders;this.machine=machine;this.qualification=qualification;this.payments=payments;this.tickets=tickets;
        this.outbox=outbox;this.gateway=gateway;this.pools=pools;this.metrics=metrics;tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> begin(String orderId,long user,PaymentRequest request){
        Map<String,Object> attempt=tx.execute(s->{
            TicketOrder order=orders.lock(orderId);orders.owner(order,user);qualification.claim(order,request,"GATEWAY_PAY");
            Map<String,Object> previous=db.one("SELECT * FROM tb_gateway_payment_attempt WHERE payment_request_id=?",request.getPaymentRequestId());
            if(previous!=null)return previous;
            BusinessException.require(payments.findByPaymentRequestId(request.getPaymentRequestId())==null,"支付编号已用于其他通道");
            BusinessException.require("WAITING_PAYMENT".equals(order.getStatus()),"订单不可再次发起支付");
            qualification.assertQualified(order);qualification.startPaying(order);
            String id=Db.id();db.jdbc.update("INSERT INTO tb_gateway_payment_attempt(id,payment_request_id,order_id,user_id,amount,currency,device_id,status,active_order,expires_at) VALUES(?,?,?,?,?,'CNY',?,'NEW',?,?)",
                    id,request.getPaymentRequestId(),orderId,user,order.getAmount(),request.getDeviceId(),orderId,order.getExpiresAt());
            return attempt(id);
        });
        drive(attempt.get("id").toString());return view(attempt.get("id").toString(),user);
    }
    public Map<String,Object> view(String id,long user){
        Map<String,Object> a=attempt(id);if(a==null)throw new BusinessException(404,"支付请求不存在");
        if(Db.number(a,"user_id")!=user)throw new BusinessException(403,"无权访问支付请求");
        Map<String,Object> value=new LinkedHashMap<>();value.put("paymentAttemptId",id);value.put("orderId",a.get("order_id"));value.put("status",a.get("status"));return value;
    }
    private Map<String,Object> attempt(String id){return db.one("SELECT * FROM tb_gateway_payment_attempt WHERE id=?",id);}
    public void drive(String id){
        Map<String,Object> a=tx.execute(s->{
            Map<String,Object> current=db.one("SELECT * FROM tb_gateway_payment_attempt WHERE id=? FOR UPDATE",id);
            if(current==null||!Arrays.asList("NEW","UNKNOWN","REFUND_PENDING").contains(current.get("status")))return null;
            long now=System.currentTimeMillis();
            if(current.get("lease_until")!=null&&((Timestamp)current.get("lease_until")).getTime()>now)return null;
            String token=Db.id();db.jdbc.update("UPDATE tb_gateway_payment_attempt SET lease_token=?,lease_until=? WHERE id=?",token,new Timestamp(now+10000),id);return attempt(id);
        });
        if(a==null)return;
        try{
            BigDecimal amount=(BigDecimal)a.get("amount");String currency=a.get("currency").toString();PaymentGateway.Observation observed;
            if("REFUND_PENDING".equals(a.get("status")))observed=gateway.refund(id,"REFUND:"+id,amount,currency);
            else if(((Timestamp)a.get("expires_at")).getTime()<=System.currentTimeMillis())observed=gateway.close(id);
            else if("NEW".equals(a.get("status")))observed=gateway.create(id,amount,currency,((Timestamp)a.get("expires_at")).getTime());
            else{
                observed=gateway.query(id);
                if(observed!=null&&observed.getStatus()==PaymentGateway.Status.NOT_FOUND)
                    observed=((Timestamp)a.get("expires_at")).getTime()>System.currentTimeMillis()
                            ?gateway.create(id,amount,currency,((Timestamp)a.get("expires_at")).getTime()):gateway.close(id);
            }
            accept(id,observed,a.get("lease_token").toString());
        }catch(RuntimeException unavailable){
            metrics.counter("gateway.operation.unavailable").increment();
            db.jdbc.update("UPDATE tb_gateway_payment_attempt SET status=CASE WHEN status='NEW' THEN 'UNKNOWN' ELSE status END,lease_token=NULL,lease_until=NULL,retry_count=retry_count+1,next_check_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=?",
                    new Timestamp(System.currentTimeMillis()+3000),id,a.get("lease_token"));
        }
    }
    public void accept(String id,PaymentGateway.Observation observed,String lease){
        BusinessException.require(observed!=null&&observed.getStatus()!=null&&id.equals(observed.getMerchantPaymentId()),"支付网关返回缺失或业务编号不匹配");
        Map<String,Object> reference=attempt(id);BusinessException.require(reference!=null,"支付请求不存在");
        tx.executeWithoutResult(s->{
            TicketOrder order=orders.lock(reference.get("order_id").toString());
            Map<String,Object> a=db.one("SELECT * FROM tb_gateway_payment_attempt WHERE id=? FOR UPDATE",id);
            boolean monetary=observed.getStatus()==PaymentGateway.Status.SUCCESS||observed.getStatus()==PaymentGateway.Status.REFUNDED;
            if(!monetary && lease!=null&&!lease.equals(a.get("lease_token")))return;
            if(monetary){
                BusinessException.require(observed.getAmount()!=null&&((BigDecimal)a.get("amount")).compareTo(observed.getAmount())==0
                        &&Objects.equals(a.get("currency"),observed.getCurrency()),"支付金额或币种不匹配");
            }
            String state=a.get("status").toString();
            if(observed.getStatus()==PaymentGateway.Status.SUCCESS){
                BusinessException.require(observed.getProviderTransactionId()!=null&&!observed.getProviderTransactionId().isEmpty()
                        &&observed.getPaidAt()!=null&&observed.getPaidAt()>0&&observed.getPaidAt()<=System.currentTimeMillis()+5000,"支付凭证或时间无效");
                if(a.get("provider_transaction_id")!=null){
                    BusinessException.require(a.get("provider_transaction_id").equals(observed.getProviderTransactionId()),"同一支付尝试出现不同资金流水");
                    if(Arrays.asList("SUCCEEDED","REFUND_PENDING","REFUNDED").contains(state))return;
                }
                boolean owned=order.getId().equals(a.get("active_order"));
                boolean valid=owned&&"PAYING".equals(order.getStatus())&&Arrays.asList("NEW","UNKNOWN").contains(state)
                        &&observed.getPaidAt()<=((Timestamp)a.get("expires_at")).getTime();
                if(valid){
                    payments.insert(Db.id(),a.get("payment_request_id").toString(),order.getId(),order.getUserId(),order.getAmount(),"PAYMENT","SUCCESS","PAYMENT:"+order.getId(),a.get("device_id").toString());
                    machine.transition(order,OrderStatus.PAID);outbox.append(order.getId(),"ISSUE");
                }else if(owned&&"PAYING".equals(order.getStatus())){
                    machine.transition(order,OrderStatus.PAY_TIMEOUT);orders.release(order);
                }
                db.jdbc.update("UPDATE tb_gateway_payment_attempt SET provider_transaction_id=?,paid_at=?,status=?,settlement_kind=?,active_order=NULL,lease_token=NULL,lease_until=NULL,next_check_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        observed.getProviderTransactionId(),new Timestamp(observed.getPaidAt()),valid?"SUCCEEDED":"REFUND_PENDING",valid?"VALID":"LATE",id);
                metrics.counter(valid?"gateway.payment.success":"gateway.payment.late_capture").increment();return;
            }
            if(observed.getStatus()==PaymentGateway.Status.REFUNDED){
                if("REFUNDED".equals(state))return;
                BusinessException.require("REFUND_PENDING".equals(state),"尚未申请退款，不能凭回调更改状态");
                boolean valid="VALID".equals(a.get("settlement_kind"));
                if(valid){
                    BusinessException.require("REFUNDING".equals(order.getStatus()),"订单退款状态不一致");
                    tickets.deleteByOrderId(order.getId());machine.transition(order,OrderStatus.REFUNDED);orders.release(order);
                }
                payments.insert(Db.id(),"GATEWAY_REFUND:"+id,order.getId(),order.getUserId(),a.get("amount"),"REFUND","SUCCESS",valid?"REFUND:"+order.getId():"LATE_REFUND:"+id,a.get("device_id").toString());
                db.jdbc.update("UPDATE tb_gateway_payment_attempt SET status='REFUNDED',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);return;
            }
            if(!Arrays.asList("NEW","UNKNOWN").contains(state))return; // stale nonmonetary reply cannot undo success/refund
            if(observed.getStatus()==PaymentGateway.Status.FAILED||observed.getStatus()==PaymentGateway.Status.CLOSED){
                if(order.getId().equals(a.get("active_order"))&&"PAYING".equals(order.getStatus())){
                    if(order.getExpiresAt().getTime()<=System.currentTimeMillis()){machine.transition(order,OrderStatus.PAY_TIMEOUT);orders.release(order);}
                    else machine.transition(order,OrderStatus.WAITING_PAYMENT);
                }
                db.jdbc.update("UPDATE tb_gateway_payment_attempt SET status=?,active_order=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",observed.getStatus().name(),id);
            }else db.jdbc.update("UPDATE tb_gateway_payment_attempt SET status='UNKNOWN',lease_token=NULL,lease_until=NULL,next_check_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",new Timestamp(System.currentTimeMillis()+1000),id);
        });
    }
    public boolean hasCaptured(String order){return db.one("SELECT id FROM tb_gateway_payment_attempt WHERE order_id=? AND settlement_kind='VALID'",order)!=null;}
    public Map<String,Object> refund(String orderId,long user,PaymentRequest request){
        String id=tx.execute(s->{
            TicketOrder order=orders.lock(orderId);orders.owner(order,user);qualification.claim(order,request,"GATEWAY_REFUND");
            Map<String,Object> a=db.one("SELECT * FROM tb_gateway_payment_attempt WHERE order_id=? AND settlement_kind='VALID' FOR UPDATE",orderId);
            BusinessException.require(a!=null,"有效网关支付不存在");
            if("SUCCEEDED".equals(a.get("status"))){machine.transition(order,OrderStatus.REFUNDING);db.jdbc.update("UPDATE tb_gateway_payment_attempt SET status='REFUND_PENDING',next_check_at=CURRENT_TIMESTAMP WHERE id=?",a.get("id"));}
            return a.get("id").toString();
        });drive(id);return view(id,user);
    }
    @Scheduled(fixedDelayString="${ticketing.payment.gateway.poll-ms:1000}") public void reconcile(){
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT id FROM tb_gateway_payment_attempt WHERE status IN ('NEW','UNKNOWN','REFUND_PENDING') AND next_check_at<=CURRENT_TIMESTAMP ORDER BY next_check_at LIMIT 16")){
            try{pools.execute("gateway",()->drive(row.get("id").toString()));}catch(java.util.concurrent.RejectedExecutionException full){return;}
        }
    }
}
