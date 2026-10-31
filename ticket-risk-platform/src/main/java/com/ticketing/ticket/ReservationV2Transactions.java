package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.*;
import com.ticketing.risk.*;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;

/** Reuses the existing stock bucket/CAS/quota/reservation/order infrastructure, with short durable intent + final TX. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationV2Transactions {
    private final Db db;private final TicketSaleArmory armory;private final ReservationDispatch queue;
    private final ReservationIntentRepository intents;private final TicketInventoryRepository skus;
    private final UserPurchaseRepository purchases;private final ReservationRepository reservations;private final OrderRepository orders;
    private final InventoryService inventory;private final RiskSubjectFence fences;private final RiskDecisionService risk;
    private final OutboxService outbox;private final ObjectMapper json;private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final TransactionTemplate attemptTx;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationQualificationOrder qualificationOrder;
    public static class Rejected extends BusinessException {
        public final String code;public Rejected(String code){super(409,"暂不具备本次购票资格或票已售罄");this.code=code;}
    }
    public ReservationV2Transactions(Db db,TicketSaleArmory armory,ReservationDispatch queue,ReservationIntentRepository intents,
            TicketInventoryRepository skus,UserPurchaseRepository purchases,ReservationRepository reservations,OrderRepository orders,
            InventoryService inventory,RiskSubjectFence fences,RiskDecisionService risk,OutboxService outbox,ObjectMapper json,
            MeterRegistry metrics,PlatformTransactionManager manager){
        this.db=db;this.armory=armory;this.queue=queue;this.intents=intents;this.skus=skus;this.purchases=purchases;
        this.reservations=reservations;this.orders=orders;this.inventory=inventory;this.fences=fences;this.risk=risk;
        this.outbox=outbox;this.json=json;this.metrics=metrics;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        attemptTx=new TransactionTemplate(manager);attemptTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        attemptTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);attemptTx.setTimeout(5);
    }
    public void prepare(Map<String,Object> claimed){
        org.springframework.transaction.support.TransactionCallback<Void> prepare=status->{
            // First lock is the Armory barrier; neither a queue token nor an earlier gate authorizes stock.
            gate(claimed,"sale_intent_gate");
            Map<String,Object> job=lockOwned(claimed);
            if(job==null)return null;
            if(ReservationAttemptStore.isAttempt(job))fences.prepare(Db.number(job,"user_id"),text(job,"device_id"),text(job,"ip"));
            ReservationIntent prior=intents.findByRequestId(text(job,"request_id"));
            if(prior==null){
                if(expired(job))throw new Rejected("QUEUE_EXPIRED");
                intents.insertRequested(text(job,"request_id"),text(job,"fingerprint"),job.get("reservation_id").toString(),job.get("order_id").toString(),
                        job.get("user_id"),job.get("sku_id"),((Number)job.get("quantity")).intValue(),text(job,"device_id"),text(job,"ip"),text(job,"id"));
            }else ReservationRuntimeRecovery.assertOwned(job,prior);
            if(qualificationOrder!=null&&ReservationAttemptStore.isAttempt(job))qualificationOrder.publish(job);
            return null;
        };
        if(ReservationAttemptStore.isAttempt(claimed))AttemptTransactionTiming.execute(attemptTx,metrics,"prepare",prepare);
        else tx.execute(prepare);
    }
    @SuppressWarnings("unchecked")
    public void reserve(Map<String,Object> claimed){
        if(qualificationOrder!=null&&ReservationAttemptStore.isAttempt(claimed))reserveInOrder(claimed);
        long start=System.nanoTime();boolean[] confirmed={false};
        try{transaction(claimed).executeWithoutResult(status->{
            TicketSku sku=gate(claimed,"sale_reserve_gate");
            Map<String,Object> job=lockOwned(claimed);if(job==null)return;
            ReservationIntent r=intents.findByRequestIdForUpdate(text(job,"request_id"));
            BusinessException.require(r!=null&&"REQUESTED".equals(r.getStatus()),"预占意图不可执行");
            ReservationRuntimeRecovery.assertOwned(job,r);
            if(!fences.lockAndCheck(r.getUserId(),r.getDeviceId(),r.getIp()))throw new Rejected("DEQUEUE_BLOCK");
            // A recovery may complete an already-held marker. New holds cannot start after queue deadline.
            if(expired(job)&&!inventory.hasRuntimeHold(r))throw new Rejected("QUEUE_EXPIRED");
            // The unchanged Lua still enforces sale end. A claimed attempt's old WAITING deadline
            // is not a stock processing deadline; do not replace it with another fixed duration.
            long deadline=ReservationAttemptStore.isAttempt(job)?sku.getSaleEndTime().getTime():((Timestamp)job.get("queue_expires_at")).getTime();
            long result;
            if(qualificationOrder!=null&&ReservationAttemptStore.isAttempt(job)){
                if(!ReservationQualificationOrder.finished(job))throw new IllegalStateException("Lua turn is not durably finished");
                inventory.loadRuntimeHold(r,Db.number(job,"sale_version"));result=1;
            }else result=inventory.reserveRuntime(r,Db.number(job,"sale_version"),deadline);
            if(result<=0)throw new Rejected(inventoryCode(result,ReservationAttemptStore.isAttempt(job)));
            Timestamp expires=r.getExpiresAt();
            if(expires==null||expires.getTime()<=System.currentTimeMillis())throw new Rejected("PAYMENT_DEADLINE_EXPIRED");
            if(!skus.tryDecreaseStockForReservation(r))throw new Rejected("MYSQL_STOCK_REJECTED");
            purchases.ensureZeroRow(r.getUserId(),r.getSkuId());
            if(!purchases.tryIncreaseWithinLimit(r.getUserId(),r.getSkuId(),r.getQuantity(),sku.getPurchaseLimit()))throw new Rejected("PURCHASE_LIMIT");
            reservations.insertHeld(r.getReservationId(),r.getRequestId(),r.getUserId(),r.getSkuId(),r.getQuantity(),expires);
            orders.insertWaitingPayment(r.getOrderId(),r.getReservationId(),r.getUserId(),r.getSkuId(),r.getQuantity(),sku.getPrice().multiply(java.math.BigDecimal.valueOf(r.getQuantity())),r.getDeviceId(),r.getIp(),r.getTraceId(),expires);
            Map<String,Object> admission;
            try{admission=json.readValue(text(job,"admission"),Map.class);}catch(Exception error){throw new IllegalStateException("Missing durable admission",error);}
            risk.saveAdmission(r.getOrderId(),admission,r.getDeviceId(),r.getIp());
            intents.markCompleted(r.getRequestId());
            db.jdbc.update("UPDATE tb_reservation_job SET state='RESERVED',result_code='RESERVED',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",job.get("id"));
            // No automatic payment and no heavyweight workflow execution in this transaction.
            outbox.append(r.getOrderId(),"RESERVATION_CREATED");
            confirmed[0]=true;
        });if(confirmed[0])metrics.counter("reservation.v2.confirmed").increment();}
        finally{metrics.timer("reservation.v2.transaction").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    /** Holds only this job/intent/security fence and its SKU turn, never the order confirmation TX. */
    private void reserveInOrder(Map<String,Object> claimed){
        long start=System.nanoTime();
        try{attemptTx.executeWithoutResult(status->{
            TicketSku sku=gate(claimed,"sale_ordered_reserve_gate");
            Map<String,Object> job=lockOwned(claimed);if(job==null)return;
            if(ReservationQualificationOrder.finished(job))return;
            ReservationIntent r=intents.findByRequestIdForUpdate(text(job,"request_id"));
            BusinessException.require(r!=null&&"REQUESTED".equals(r.getStatus()),"预占意图不可执行");
            ReservationRuntimeRecovery.assertOwned(job,r);
            if(!fences.lockAndCheck(r.getUserId(),r.getDeviceId(),r.getIp()))throw new Rejected("DEQUEUE_BLOCK");
            qualificationOrder.requireTurn(job);
            long result=inventory.reserveRuntime(r,Db.number(job,"sale_version"),sku.getSaleEndTime().getTime());
            if(result<=0)throw new Rejected(inventoryCode(result,true));
            qualificationOrder.passed(job);
        });}finally{metrics.timer("attempt.ordered.lua.transaction").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    public void abort(Map<String,Object> claimed,String code){
        transaction(claimed).executeWithoutResult(status->{
            Map<String,Object> job=lockOwned(claimed);if(job==null)return;
            ReservationIntent r=intents.findByRequestIdForUpdate(text(job,"request_id"));
            if(r!=null){
                ReservationRuntimeRecovery.assertOwned(job,r);
                if("COMPLETED".equals(r.getStatus()))return; // Never compensate an ambiguous successful commit.
                inventory.abortRuntime(r,Db.number(job,"sale_version"));
                if(qualificationOrder!=null)qualificationOrder.aborted(job);
                intents.tryMarkFailedIfRequested(r.getRequestId());
            }
            boolean block="DEQUEUE_BLOCK".equals(code),expired=code.contains("EXPIRED");
            String decision=block?"BLOCK":text(job,"decision");
            queue.audit(job,"DEQUEUE",decision,code,Collections.emptyMap());
            db.jdbc.update("UPDATE tb_reservation_job SET state=?,decision=?,result_code=?,active_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    block?"REJECTED":expired?"EXPIRED":ReservationAttemptStore.isAttempt(job)&&"SOLD_OUT".equals(code)?"SOLD_OUT":"FAILED",decision,code,job.get("id"));
        });
    }
    private String inventoryCode(long result,boolean attempt){return result==-3||result==-11?"SOLD_OUT":result==-2?"PURCHASE_LIMIT":result==-13?"SINGLE_PURCHASE_LIMIT":result==-12?"INSUFFICIENT_STOCK":result==-10?(attempt?"SALE_WINDOW_CLOSED":"QUEUE_EXPIRED"):"INVENTORY_REJECTED";}
    private Map<String,Object> lockOwned(Map<String,Object> claimed){
        Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",claimed.get("id"));
        return queue.owns(job,claimed,ReservationAttemptStore.isAttempt(claimed)?"PROCESSING":"CLAIMED")?job:null;
    }
    private TicketSku gate(Map<String,Object> claimed,String operation){
        long begin=System.nanoTime();String outcome="success";
        try{return armory.runtimeSku(text(claimed,"session_id"),text(claimed,"sku_id"),Db.number(claimed,"sale_version"));}
        catch(RuntimeException|Error failure){outcome="failure";throw failure;}
        finally{metrics.timer("ticketing.operation.duration","operation",operation,"outcome",outcome).record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    private TransactionTemplate transaction(Map<String,Object> job){return ReservationAttemptStore.isAttempt(job)?attemptTx:tx;}
    private boolean expired(Map<String,Object> job){return !ReservationAttemptStore.isAttempt(job)&&((Timestamp)job.get("queue_expires_at")).getTime()<=System.currentTimeMillis();}
    static String text(Map<String,Object> value,String key){Object v=value.get(key);return v==null?null:v.toString();}
}
