package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.sale.TicketSaleArmory;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded notification-driven sweep, never an enduring SKU sold-out latch. */
@Service
@ConditionalOnProperty(name="ticketing.inventory.vector-reservations",havingValue="true")
public class ReservationSoldOutSweep {
    private final Db db;private final TicketSaleArmory armory;private final InventoryService inventory;private final ReservationQualificationOrder order;
    private final ReservationIntentRepository intents;private final TransactionTemplate tx;
    private final String locking;
    private final Map<String,Long> pending=new ConcurrentHashMap<>();private long next;
    public ReservationSoldOutSweep(Db db,TicketSaleArmory armory,InventoryService inventory,ReservationQualificationOrder order,ReservationIntentRepository intents,PlatformTransactionManager manager){
        this.db=db;this.armory=armory;this.inventory=inventory;this.order=order;this.intents=intents;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(3);
        String product=db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());locking=product.contains("MySQL")?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
    }
    public void signal(Map<String,Object> job){String sku=job.get("sku_id").toString();if(pending.size()<128||pending.containsKey(sku))pending.merge(sku,Db.number(job,"sale_version"),Math::max);}
    public synchronized void run(){
        long now=System.nanoTime();if(now<next||pending.isEmpty())return;next=now+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);int visited=0;
        for(Map.Entry<String,Long> entry:pending.entrySet()){
            if(visited++>=2)return;
            try{
                List<Map<String,Object>> jobs=db.jdbc.queryForList("SELECT id FROM tb_reservation_job WHERE sku_id=? AND sale_version=? AND attempt_protocol=3 AND (state='WAITING' OR (state='PROCESSING' AND lua_turn_finished=0)) ORDER BY created_at,id LIMIT 8",entry.getKey(),entry.getValue());
                if(jobs.isEmpty()){pending.remove(entry.getKey(),entry.getValue());continue;}
                for(Map<String,Object> hint:jobs)if(!sweep(hint.get("id").toString())){pending.remove(entry.getKey(),entry.getValue());break;}
            }catch(RuntimeException unavailable){pending.remove(entry.getKey(),entry.getValue());/* workers still enforce the atomic stock check */}
        }
    }
    private boolean sweep(String id){return tx.execute(status->{
        Map<String,Object> candidate=db.one("SELECT * FROM tb_reservation_job WHERE id=?",id);if(candidate==null)return true;
        armory.runtimeSku(candidate.get("session_id").toString(),candidate.get("sku_id").toString(),Db.number(candidate,"sale_version"));
        Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE id=?"+locking,id);if(job==null)return true;
        if(!Arrays.asList("WAITING","PROCESSING").contains(job.get("state"))||ReservationQualificationOrder.finished(job))return true;
        ReservationIntent intent=intents.findByRequestIdForUpdate(job.get("request_id").toString());
        if(intent==null){intent=new ReservationIntent();intent.setSkuId(job.get("sku_id").toString());intent.setUserId(Db.number(job,"user_id"));intent.setReservationId(job.get("reservation_id").toString());intent.setFingerprint(job.get("fingerprint").toString());}
        String deadline="WAITING".equals(job.get("state"))?Long.toString(((java.sql.Timestamp)job.get("waiting_deadline")).getTime()):"";
        if(!deadline.isEmpty()&&Long.parseLong(deadline)<=db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).getTime())return true;
        long result=inventory.markSoldOut(intent,Db.number(job,"sale_version"),deadline);
        if(result==2||result==3)return true;if(result!=1)return false;
        if(ReservationQualificationOrder.published(job)){
            try{order.aborted(job);}catch(ReservationQualificationOrder.Pending earlier){
                db.jdbc.update("UPDATE tb_reservation_job SET result_code='SOLD_OUT_PENDING',lease_token=NULL,lease_until=CURRENT_TIMESTAMP(3) WHERE id=?",id);return true;
            }
        }
        if(intent.getRequestId()!=null)intents.tryMarkFailedIfRequested(intent.getRequestId());
        db.jdbc.update("UPDATE tb_reservation_job SET state='SOLD_OUT',result_code='SOLD_OUT',active_owner=NULL,lease_token=NULL,lease_until=NULL WHERE id=? AND state IN ('WAITING','PROCESSING')",id);
        return true;
    });}
}
