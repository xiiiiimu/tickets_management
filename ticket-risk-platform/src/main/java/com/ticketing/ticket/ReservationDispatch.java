package com.ticketing.ticket;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import javax.annotation.PostConstruct;

/** Durable bounded queue. The coordinator serializes admission/selection, not inventory transactions. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationDispatch {
    private final Db db;private final RiskSubjectFence fences;private final TransactionTemplate tx;
    private final String skip;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationDispatchScheduler scheduler;
    @Value("${ticketing.reservations-v2.queue-capacity:512}") private int capacity;
    @Value("${ticketing.reservations-v2.workers:4}") private int workers;
    @Value("${ticketing.reservations-v2.pass-weight:2}") private int passWeight;
    @Value("${ticketing.reservations-v2.review-age-ms:2000}") private long reviewAge;
    @Value("${ticketing.reservations-v2.queue-timeout-ms:30000}") private long queueTimeout;
    @Value("${ticketing.reservations-v2.attempts.enabled:false}") private boolean durableAttempts;
    @Value("${ticketing.reservations-v3.enabled:false}") private boolean v3;
    public ReservationDispatch(Db db,RiskSubjectFence fences,PlatformTransactionManager manager){
        this.db=db;this.fences=fences;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        String product=db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        skip=product!=null&&product.contains("MySQL")?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
    }
    @PostConstruct public void initialize(){
        db.jdbc.queryForList("SELECT queued_at,claimed_at FROM tb_reservation_job WHERE 1=0");
        if(capacity<1||workers<1||workers>32||passWeight<1||passWeight>100||queueTimeout<1000||reviewAge<1)throw new IllegalArgumentException("Invalid reservation scheduler bounds");
        db.jdbc.update("INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms) VALUES(1,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",passWeight,capacity,workers,reviewAge);
        Map<String,Object> row=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1");
        long protocol=row.get("runtime_protocol") instanceof Number?Db.number(row,"runtime_protocol"):2;
        if(protocol!=(v3?4:durableAttempts?3:2))throw new IllegalStateException("All instances must use the explicitly activated runtime protocol");
        if(!durableAttempts&&db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('WAITING','PROCESSING')",Long.class)!=0)
            throw new IllegalStateException("Durable attempts must drain before switching back to the legacy protocol");
        if(Db.number(row,"pass_weight")!=passWeight||Db.number(row,"capacity")!=capacity||Db.number(row,"active_limit")!=workers||Db.number(row,"review_age_ms")!=reviewAge)
            throw new IllegalStateException("All reservation instances must use the persisted scheduler settings");
    }
    public Map<String,Object> byRequest(String request){return db.one("SELECT j.* FROM tb_reservation_job j WHERE j.request_id=? UNION ALL "
            +"SELECT j.* FROM tb_reservation_request_alias a JOIN tb_reservation_job j ON j.id=a.job_id WHERE a.request_id=? LIMIT 1",request,request);}
    public Map<String,Object> byReservation(String reservation){return db.one("SELECT * FROM tb_reservation_job WHERE reservation_id=?",reservation);}
    public Map<String,Object> get(String id){return db.one("SELECT * FROM tb_reservation_job WHERE id=?",id);}
    public Map<String,Object> create(ReserveRequest r,String ip,String fingerprint,long version){
        if(durableAttempts)throw new IllegalStateException("Protocol 3 must use reliable WAITING registration");
        return tx.execute(status->{
            Map<String,Object> settings=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE");
            Map<String,Object> previous=byRequest(r.getRequestId());
            if(previous!=null){match(previous,r,fingerprint);return previous;}
            String owner=r.getUserId()+":"+r.getEventSessionId();
            Map<String,Object> active=db.one("SELECT * FROM tb_reservation_job WHERE active_owner=? FOR UPDATE",owner);
            if(active!=null){
                String order=active.get("order_id").toString();Map<String,Object> state=db.one("SELECT status FROM tb_ticket_order WHERE id=?",order);
                boolean terminal=state!=null&&Arrays.asList("PAID","ISSUED","PAY_TIMEOUT","CANCELLED","REFUNDED","RISK_BLOCKED").contains(state.get("status"));
                if(!terminal){match(active,r,fingerprint);
                    if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_request_alias WHERE job_id=?",Long.class,active.get("id"))>=32)
                        throw new BusinessException(429,"重复请求过多，请使用原请求编号查询");
                    db.jdbc.update("INSERT INTO tb_reservation_request_alias(request_id,job_id) VALUES(?,?)",r.getRequestId(),active.get("id"));return active;}
                db.jdbc.update("UPDATE tb_reservation_job SET active_owner=NULL WHERE id=?",active.get("id"));
            }
            BusinessException.require(db.one("SELECT request_id FROM tb_reserve_request WHERE request_id=?",r.getRequestId())==null,"请求标识已被旧链路使用");
            long pending=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('EVALUATING','QUEUED','CLAIMED')",Long.class);
            if(pending>=Db.number(settings,"capacity"))throw new BusinessException(429,"抢票队列已满，请稍后重试");
            fences.prepare(r.getUserId(),r.getDeviceId(),ip);
            String id=Db.id(),token=Db.id();long now=System.currentTimeMillis();
            db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,lease_token,lease_until,queue_expires_at,reservation_id,order_id,active_owner) "
                    +"VALUES(?,?,?,?,?,?,?,?,?,?,'EVALUATING',?,?,?,?,?,?)",id,r.getRequestId(),fingerprint,r.getUserId(),r.getEventSessionId(),r.getTicketSkuId(),r.getQuantity(),r.getDeviceId(),ip,version,
                    token,new Timestamp(now+15000),new Timestamp(now+queueTimeout),Db.id(),Db.id(),owner);
            Map<String,Object> result=get(id);result.put("newlyCreated",true);return result;
        });
    }
    public void match(Map<String,Object> job,ReserveRequest r,String fingerprint){
        if(Db.number(job,"user_id")!=r.getUserId())throw new BusinessException(403,"无权访问该请求");
        BusinessException.require(fingerprint.equals(job.get("fingerprint")),"请求标识或场次已用于其他购票参数");
    }
    public void decided(Map<String,Object> job,Map<String,Object> admission){
        tx.executeWithoutResult(status->{
            Map<String,Object> current=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",job.get("id"));
            if(!owns(current,job,"EVALUATING"))return;
            boolean expired=((Timestamp)current.get("queue_expires_at")).getTime()<=System.currentTimeMillis();
            String decision=admission.get("decision").toString(),code=admission.get("resultCode").toString();
            String next=expired?"EXPIRED":"BLOCK".equals(decision)?"REJECTED":"QUEUED";
            if(expired)code="QUEUE_EXPIRED";
            audit(current,"ENTRY",decision,code,admission);
            db.jdbc.update("UPDATE tb_reservation_job SET state=?,decision=?,result_code=?,admission=?,lease_token=NULL,lease_until=NULL,active_owner=?,queued_at=CASE WHEN ?='QUEUED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                    next,decision,code,db.json(admission),"QUEUED".equals(next)?current.get("active_owner"):null,next,current.get("id"));
            if("QUEUED".equals(next)&&scheduler!=null)scheduler.afterCommitWake();
        });
    }
    public void evaluationFailed(Map<String,Object> job,String code){
        tx.executeWithoutResult(status->{
            Map<String,Object> current=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",job.get("id"));
            if(!owns(current,job,"EVALUATING"))return;
            audit(current,"ENTRY",null,code,Collections.emptyMap());
            db.jdbc.update("UPDATE tb_reservation_job SET state=?,result_code=?,active_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",code.contains("EXPIRED")?"EXPIRED":"FAILED",code,job.get("id"));
        });
    }
    public Map<String,Object> claim(){
        return tx.execute(status->{
            Map<String,Object> settings=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE");long now=System.currentTimeMillis();
            Map<String,Object> stale=db.one("SELECT * FROM tb_reservation_job WHERE state IN ('EVALUATING','CLAIMED') AND lease_until<? ORDER BY lease_until LIMIT 1"+skip,new Timestamp(now));
            if(stale!=null)return renew(stale,stale.get("state").toString(),now);
            long active=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'",Long.class);
            if(active>=Db.number(settings,"active_limit"))return null;
            Map<String,Object> review=db.one("SELECT * FROM tb_reservation_job WHERE state='QUEUED' AND decision='REVIEW' ORDER BY created_at,id LIMIT 1"+skip);
            boolean age=review!=null&&now-((Timestamp)review.get("created_at")).getTime()>=Db.number(settings,"review_age_ms");
            boolean preferReview=review!=null&&((age&&Db.number(settings,"pass_run")>=1)||Db.number(settings,"pass_run")>=Db.number(settings,"pass_weight"));
            Map<String,Object> pass=preferReview?null:db.one("SELECT * FROM tb_reservation_job WHERE state='QUEUED' AND decision='PASS' ORDER BY created_at,id LIMIT 1"+skip);
            Map<String,Object> selected=pass!=null?pass:review;
            if(selected==null)return null;
            db.jdbc.update("UPDATE tb_reservation_dispatch SET pass_run=? WHERE id=1","PASS".equals(selected.get("decision"))?Math.min(passWeight,Db.number(settings,"pass_run")+1):0);
            return renew(selected,"CLAIMED",now);
        });
    }
    private Map<String,Object> renew(Map<String,Object> job,String state,long now){
        String token=Db.id();db.jdbc.update("UPDATE tb_reservation_job SET state=?,lease_token=?,lease_until=?,claimed_at=CASE WHEN ?='CLAIMED' THEN CURRENT_TIMESTAMP(3) ELSE claimed_at END,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",state,token,new Timestamp(now+15000),state,job.get("id"));return get(job.get("id").toString());
    }
    /** Same coordinator/lease protocol as claim(), bounded to the existing dispatch quantum. */
    public List<Map<String,Object>> claimBatch(int maximum){
        if(maximum<1||maximum>4)throw new IllegalArgumentException("Claim batch must be between 1 and 4");
        return tx.execute(status->{
            Map<String,Object> settings=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE");
            List<Map<String,Object>> result=new ArrayList<>();long now=System.currentTimeMillis();
            List<Map<String,Object>> stale=db.jdbc.queryForList("SELECT * FROM tb_reservation_job WHERE state IN ('EVALUATING','CLAIMED') AND lease_until<? ORDER BY lease_until LIMIT ?"+skip,new Timestamp(now),maximum);
            for(Map<String,Object> job:stale)result.add(renew(job,job.get("state").toString(),System.currentTimeMillis()));
            long active=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'",Long.class);
            long available=Math.max(0,Db.number(settings,"active_limit")-active);
            long run=Db.number(settings,"pass_run"),initialRun=run;
            while(result.size()<maximum&&available>0){
                now=System.currentTimeMillis();
                Map<String,Object> review=db.one("SELECT * FROM tb_reservation_job WHERE state='QUEUED' AND decision='REVIEW' ORDER BY created_at,id LIMIT 1"+skip);
                boolean age=review!=null&&now-((Timestamp)review.get("created_at")).getTime()>=Db.number(settings,"review_age_ms");
                boolean preferReview=review!=null&&((age&&run>=1)||run>=Db.number(settings,"pass_weight"));
                Map<String,Object> pass=preferReview?null:db.one("SELECT * FROM tb_reservation_job WHERE state='QUEUED' AND decision='PASS' ORDER BY created_at,id LIMIT 1"+skip);
                Map<String,Object> selected=pass!=null?pass:review;
                if(selected==null)break;
                run="PASS".equals(selected.get("decision"))?Math.min(Db.number(settings,"pass_weight"),run+1):0;
                result.add(renew(selected,"CLAIMED",now));available--;
            }
            if(run!=initialRun)db.jdbc.update("UPDATE tb_reservation_dispatch SET pass_run=? WHERE id=1",run);
            return result;
        });
    }
    public void yieldClaim(Map<String,Object> job){
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=? AND lease_token=? AND state IN ('EVALUATING','CLAIMED')",new Timestamp(1000),job.get("id"),job.get("lease_token"));
    }
    public boolean owns(Map<String,Object> current,Map<String,Object> claimed,String state){
        return current!=null&&state.equals(current.get("state"))&&Objects.equals(current.get("lease_token"),claimed.get("lease_token"));
    }
    public void audit(Map<String,Object> job,String phase,String decision,String code,Map<String,Object> detail){
        db.jdbc.update("INSERT INTO tb_reservation_risk_audit(id,job_id,phase,decision,result_code,detail) VALUES(?,?,?,?,?,?)",Db.id(),job.get("id"),phase,decision,code,db.json(detail));
    }
    public Map<String,Object> view(Map<String,Object> job,long user){
        if(job==null)throw new BusinessException(404,"抢票请求不存在");
        if(Db.number(job,"user_id")!=user)throw new BusinessException(403,"无权访问该请求");
        Map<String,Object> result=new LinkedHashMap<>();String state=job.get("state").toString();
        result.put("reservationId",job.get("reservation_id"));result.put("requestId",job.get("request_id"));
        result.put("accepted",true);result.put("reservationSuccess","RESERVED".equals(state));result.put("state",state);
        if(ReservationAttemptStore.isAttempt(job)){
            result.put("attemptId",job.get("id"));result.put("reliableAccepted",true);result.put("authoritative",true);
            boolean ongoing=Arrays.asList("WAITING","PROCESSING").contains(state);
            result.put("terminal",!ongoing);result.put("pollingSuggested",ongoing);result.put("receivedAt",((Timestamp)job.get("created_at")).getTime());
            result.put("waitingDeadline",((Timestamp)job.get("waiting_deadline")).getTime());
            result.put("waitExpired","QUEUE_TIMEOUT".equals(state));
            if("WAITING".equals(state))result.put("waitUntil",((Timestamp)job.get("waiting_deadline")).getTime());
            if(job.get("processing_started_at")!=null)result.put("processingStartedAt",((Timestamp)job.get("processing_started_at")).getTime());
        }
        result.put("resultCode","REJECTED".equals(state)?"ELIGIBILITY_DENIED":job.get("result_code"));
        if(ReservationAttemptStore.isAttempt(job)){
            if("REJECTED".equals(state)&&"RISK_REJECTED".equals(job.get("result_code")))result.put("resultCode","RISK_REJECTED");
            if("FAILED".equals(state)&&Arrays.asList("PROFILE_UNAVAILABLE","REVIEW_BUSY","REVIEW_UNAVAILABLE","RISK_SIGNAL_UNAVAILABLE","REVIEW_CONTEXT_UNAVAILABLE","RISK_VALIDATION_UNAVAILABLE").contains(job.get("result_code"))){
                result.put("validationUnavailable",true);result.put("retryable",true);result.put("retryWithNewAttempt",true);
                result.put("message","资格校验暂不可用，本次未取得抢票资格；请稍后主动发起新的attempt。");
            }
        }
        if("RESERVED".equals(state)){
            Map<String,Object> order=db.one("SELECT status,expires_at FROM tb_ticket_order WHERE id=?",job.get("order_id"));
            BusinessException.require(order!=null,"可靠预占记录不完整");
            result.put("orderId",job.get("order_id"));result.put("orderStatus",order.get("status"));result.put("expiresAt",((Timestamp)order.get("expires_at")).getTime());
            if(Arrays.asList("PAY_TIMEOUT","CANCELLED","REFUNDED").contains(order.get("status"))){result.put("state",order.get("status"));result.put("reservationSuccess",false);}
        }
        return result;
    }
}
