package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

/** Active-user summaries, invalidated by events committed atomically with their source mutations. */
@Service
public class RiskHistorySummary {
    private final Db db; private final ObjectMapper json; private final TransactionTemplate tx;
    private final Semaphore permits=new Semaphore(32);
    private final ConcurrentHashMap<Long,CompletableFuture<Void>> flights=new ConcurrentHashMap<>();
    @Value("${ticketing.risk.summary.enabled:false}") private boolean enabled;
    public RiskHistorySummary(Db db,ObjectMapper json,PlatformTransactionManager manager){
        this.db=db;this.json=json;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public boolean isEnabled(){return enabled;}
    public void changed(Object user,String source){
        if(!enabled)return;
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Risk feature event needs source transaction");
        db.jdbc.update("INSERT INTO tb_risk_summary_event(user_id,source_key) VALUES(?,?) ON DUPLICATE KEY UPDATE source_key=source_key",user,source);
    }
    public Map<String,Double> read(long user,String device){
        if(!enabled)return null;
        Map<String,Object> row=ready(user,device);
        if(row==null){refresh(user);row=ready(user,device);}
        if(row==null)return null; // New source event raced refresh: caller uses authoritative history SQL.
        try{
            Map<?,?> payload=json.readValue(row.get("payload").toString(),Map.class);
            Map<String,Double> result=new HashMap<>();
            for(Map.Entry<?,?> e:payload.entrySet())result.put(e.getKey().toString(),((Number)e.getValue()).doubleValue());
            result.put("accountAgeHours",(System.currentTimeMillis()-((Timestamp)row.get("create_time")).getTime())/3600000d);
            result.put("newDevice",row.get("known_device")==null?1d:0d);
            return result;
        }catch(Exception error){throw new IllegalStateException("Invalid risk summary; never default allow",error);}
    }
    private Map<String,Object> ready(long user,String device){
        return db.one("SELECT s.payload,u.create_time,k.device_id known_device FROM tb_risk_user_summary s "
                +"JOIN tb_user u ON u.id=s.user_id LEFT JOIN tb_risk_known_device k ON k.user_id=s.user_id AND k.device_id=? "
                +"WHERE s.user_id=? AND s.captured_at>=? AND NOT EXISTS "
                +"(SELECT 1 FROM tb_risk_summary_event e WHERE e.user_id=s.user_id AND e.processed=false)",
                device,user,new Timestamp(System.currentTimeMillis()-30000));
    }
    void refresh(long user){
        if(!permits.tryAcquire())throw new BusinessException(503,"风险特征服务繁忙，请稍后重试");
        CompletableFuture<Void> mine=new CompletableFuture<>(),prior=flights.putIfAbsent(user,mine);
        if(prior!=null){
            permits.release();
            try{prior.get(5,TimeUnit.SECONDS);return;}catch(Exception error){throw new BusinessException(503,"风险特征暂不可用");}
        }
        try{tx.executeWithoutResult(status->rebuild(user));mine.complete(null);}
        catch(RuntimeException error){mine.completeExceptionally(error);throw error;}
        finally{flights.remove(user,mine);permits.release();}
    }
    private void rebuild(long user){
        // Serialize builders across JVMs before establishing a consistent read snapshot.
        // Otherwise an older builder could overwrite a newer summary after its events were consumed.
        db.jdbc.update("INSERT INTO tb_risk_user_summary(user_id,payload,captured_at) VALUES(?,'{}',?) "
                +"ON DUPLICATE KEY UPDATE user_id=user_id",user,new Timestamp(1000));
        // First consistent read establishes the snapshot. Mark only these event IDs, never MAX(id):
        // an older auto_increment ID may commit after this snapshot and must remain dirty.
        List<Map<String,Object>> events=db.jdbc.queryForList("SELECT id FROM tb_risk_summary_event WHERE user_id=? AND processed=false ORDER BY id LIMIT 256",user);
        Map<String,Object> h=db.one("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status IN ('CANCELLED','PAY_TIMEOUT') THEN 1 ELSE 0 END),0) cancelled,"
                +"COALESCE(SUM(CASE WHEN status='REFUNDED' THEN 1 ELSE 0 END),0) refunded,"
                +"COALESCE(SUM(CASE WHEN status IN ('PAID','ISSUED') THEN 1 ELSE 0 END),0) succeeded FROM tb_ticket_order WHERE user_id=?",user);
        double total=Db.number(h,"total");Map<String,Double> f=new HashMap<>();
        f.put("historyCount",total);f.put("cancelRate",total==0?0:Db.number(h,"cancelled")/total);
        f.put("refundRate",total==0?0:Db.number(h,"refunded")/total);f.put("successOrders",(double)Db.number(h,"succeeded"));
        int failures=0;
        for(Map<String,Object> p:db.jdbc.queryForList("SELECT status FROM tb_payment_transaction WHERE user_id=? AND type='PAYMENT' ORDER BY created_at DESC,id DESC LIMIT 30",user)){
            if("SUCCESS".equals(p.get("status")))break;if("FAILED".equals(p.get("status")))failures++;
        }
        f.put("paymentFailures",(double)failures);
        db.jdbc.update("INSERT INTO tb_risk_known_device(user_id,device_id) SELECT DISTINCT user_id,device_id FROM tb_payment_transaction "
                +"WHERE user_id=? AND type='PAYMENT' AND status='SUCCESS' ON DUPLICATE KEY UPDATE device_id=VALUES(device_id)",user);
        db.jdbc.update("INSERT INTO tb_risk_user_summary(user_id,payload,captured_at) VALUES(?,?,CURRENT_TIMESTAMP) "
                +"ON DUPLICATE KEY UPDATE payload=VALUES(payload),captured_at=CURRENT_TIMESTAMP",user,db.json(f));
        if(!events.isEmpty()){
            List<Object> ids=new ArrayList<>();for(Map<String,Object> event:events)ids.add(event.get("id"));
            String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
            db.jdbc.update("UPDATE tb_risk_summary_event SET processed=true WHERE id IN ("+marks+")",ids.toArray());
        }
    }
    @Scheduled(fixedDelayString="${ticketing.risk.summary.interval-ms:1000}")
    public void refreshPending(){
        if(!enabled)return;
        // Bounded batch; failures retain unprocessed events and source truth remains available.
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT DISTINCT user_id FROM tb_risk_summary_event WHERE processed=false LIMIT 16")){
            try{refresh(Db.number(row,"user_id"));}catch(RuntimeException failure){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Risk summary refresh deferred user={}",row.get("user_id"),failure);
            }
        }
    }
}
