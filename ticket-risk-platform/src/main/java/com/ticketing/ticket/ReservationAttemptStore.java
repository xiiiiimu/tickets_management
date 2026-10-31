package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/** Protocol 3 lifecycle on the existing job table. No inventory, risk or Redis side effects. */
@Service
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled"},havingValue="true")
public class ReservationAttemptStore {
    public static final long WAIT_MILLIS=5000,LEASE_MILLIS=15000;
    private final Db db;private final ReservationDispatch queue;private final TransactionTemplate tx;
    private final MeterRegistry metrics;private final AttemptAdmissionBudget budget;private final String skip;
    private final AttemptAdmissionDiagnostics diagnostics;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptWorkerReadiness readiness;
    private final int userLimit;
    private final boolean orderedLua;
    private final boolean quickReview;
    private final boolean vectorInventory;
    private final boolean redisLane;
    /** V3 (protocol 4): Redis Stream acceptance and dispatch; this store only serves leases/decisions for protocol-4 rows. */
    private final boolean v3;
    private Supplier<Timestamp> time;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationDispatchScheduler scheduler;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptAdmissionGate gate;
    public ReservationAttemptStore(Db db,ReservationDispatch queue,PlatformTransactionManager manager,MeterRegistry metrics,Environment env){
        this.db=db;this.queue=queue;this.metrics=metrics;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(3);
        budget=new AttemptAdmissionBudget(env,db.jdbc.getDataSource());
        diagnostics=new AttemptAdmissionDiagnostics(metrics,db.jdbc.getDataSource());
        userLimit=env.getProperty("ticketing.ingress.per-user-inflight",Integer.class,4);
        orderedLua=env.getProperty("ticketing.reservations-v2.attempts.ordered-lua",Boolean.class,false);
        quickReview=env.getProperty("ticketing.reservations-v2.attempts.quick-review",Boolean.class,false);
        vectorInventory=env.getProperty("ticketing.inventory.vector-reservations",Boolean.class,false);
        redisLane=env.getProperty("ticketing.reservations-v2.attempts.redis-ordered-lane",Boolean.class,false);
        v3=env.getProperty("ticketing.reservations-v3.enabled",Boolean.class,false);
        if(v3&&!redisLane)throw new IllegalStateException("V3 requires the Redis arrival lane (ordered-lua + redis-ordered-lane)");
        if(redisLane&&!orderedLua)throw new IllegalStateException("Redis arrival lane is a mode of the ordered qualification protocol");
        if(vectorInventory&&!orderedLua)throw new IllegalStateException("Vector inventory requires durable ordered qualification");
        if(quickReview&&!orderedLua)throw new IllegalStateException("Quick review requires the ordered qualification protocol");
        if(userLimit<1)throw new IllegalArgumentException("Invalid per-user attempt limit");
        String product=db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        skip=product!=null&&product.contains("MySQL")?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
        time=()->db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class);
        if(product!=null&&product.contains("MySQL")){
            String url=env.getProperty("spring.datasource.url","");
            if(!positiveUrlTimeout(url,"connectTimeout")||!positiveUrlTimeout(url,"socketTimeout"))
                throw new IllegalStateException("Durable attempts require explicit positive JDBC connectTimeout/socketTimeout; do not change the existing URL automatically");
        }
    }
    private static boolean positiveUrlTimeout(String url,String name){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:[?&])"+name+"=([0-9]+)(?:&|$)").matcher(url);
        if(!m.find())return false;try{long value=Long.parseLong(m.group(1));return value>0&&value<=30000;}catch(NumberFormatException error){return false;}
    }
    @javax.annotation.PostConstruct public void initialize(){
        if(scheduler!=null&&!scheduler.isIsolated())throw new IllegalStateException("Durable attempt expiry requires the existing isolated dispatch control; summary refresh must not block it");
        db.jdbc.queryForList("SELECT attempt_protocol,waiting_deadline,processing_started_at FROM tb_reservation_job WHERE 1=0");
        Map<String,Object> settings=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1");
        long runtime=Db.number(settings,"runtime_protocol");
        // Whole-system switch: protocol 3 (MySQL WAITING + coordinator lock) and protocol 4 (V3 Redis Stream) never run together.
        if(v3&&runtime!=4)throw new IllegalStateException("V3 requires tb_reservation_dispatch.runtime_protocol=4 after draining protocol 3");
        if(!v3&&runtime!=3)throw new IllegalStateException("Protocol 3 must be explicitly activated after draining legacy work");
        if(v3){
            if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol=3 AND state IN ('WAITING','PROCESSING')",Long.class)!=0)
                throw new IllegalStateException("Drain protocol-3 WAITING/PROCESSING attempts before enabling V3");
        }else if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol=4 AND state='PROCESSING'",Long.class)!=0)
            throw new IllegalStateException("Drain protocol-4 (V3) PROCESSING attempts before rolling back to protocol 3");
        long storedOrder=settings.get("ordered_lua_enabled") instanceof Number?Db.number(settings,"ordered_lua_enabled"):0;
        if(storedOrder!=(orderedLua?1:0))throw new IllegalStateException("All nodes must use the explicitly activated SKU order mode");
        long storedQuick=settings.get("quick_review_enabled") instanceof Number?Db.number(settings,"quick_review_enabled"):0;
        if(storedQuick!=(quickReview?1:0))throw new IllegalStateException("All nodes must use the explicitly activated quick review mode");
        long storedVector=settings.get("vector_inventory_enabled") instanceof Number?Db.number(settings,"vector_inventory_enabled"):0;
        if(storedVector!=(vectorInventory?1:0))throw new IllegalStateException("All nodes must use the explicitly activated inventory protocol");
        if(vectorInventory){db.jdbc.queryForList("SELECT single_purchase_limit FROM tb_ticket_sku WHERE 1=0");db.jdbc.queryForList("SELECT reservation_id,bucket_id,quantity FROM tb_stock_bucket_allocation_item WHERE 1=0");}
        // Missing column (V114 not applied) means "never activated"; activating without the column is refused.
        long storedLane=settings.get("redis_lane_enabled") instanceof Number?Db.number(settings,"redis_lane_enabled"):0;
        if(redisLane&&!settings.containsKey("redis_lane_enabled"))throw new IllegalStateException("Apply V114 before enabling the Redis arrival lane");
        if(storedLane!=(redisLane?1:0))throw new IllegalStateException("All nodes must use the explicitly activated SKU lane mode");
        if(orderedLua){db.jdbc.queryForList("SELECT qualification_seq,lua_turn_finished FROM tb_reservation_job WHERE 1=0");db.jdbc.queryForList("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE 1=0");
            if(redisLane){
                // A turn published on the SQL cursor must finish under that cursor; otherwise its successors could never pass.
                if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='PROCESSING' AND qualification_seq IS NOT NULL AND lua_turn_finished=0",Long.class)!=0)
                    throw new IllegalStateException("Drain SKU-cursor turns before enabling the Redis arrival lane");
            }else if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='PROCESSING' AND admission IS NOT NULL AND qualification_seq IS NULL",Long.class)!=0)
                throw new IllegalStateException("Drain unordered processing before enabling SKU order");}
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('EVALUATING','QUEUED','CLAIMED')",Long.class)!=0)
            throw new IllegalStateException("Drain legacy jobs before enabling durable attempts");
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request r LEFT JOIN tb_reservation_job j ON j.request_id=r.request_id WHERE r.status='REQUESTED' AND (j.id IS NULL OR j.attempt_protocol<>?)",Long.class,v3?4:3)!=0)
            throw new IllegalStateException("Resolve legacy recovery intents before enabling durable attempts");
    }
    // Package-private deterministic clock injection; never exposed through an application endpoint.
    void clock(Supplier<Timestamp> clock){time=Objects.requireNonNull(clock);}
    /** Durable attempt rows: protocol 3 (MySQL WAITING) and protocol 4 (V3, inserted when a Stream consumer starts it). */
    public static boolean isAttempt(Map<String,Object> job){if(job==null||!(job.get("attempt_protocol") instanceof Number))return false;long p=Db.number(job,"attempt_protocol");return p==3||p==4;}
    public static boolean isV3(Map<String,Object> job){return job!=null&&job.get("attempt_protocol") instanceof Number&&Db.number(job,"attempt_protocol")==4;}
    public boolean dependenciesAvailable(){return budget.dependenciesAvailable();}
    public AttemptAdmissionException dbBusy(){return diagnostics.dbBusy();}
    /** Advisory Redis gate refusal; recorded with the same internal classification as other refusals. */
    public AttemptAdmissionException gateRejected(){return diagnostics.gateRejected();}
    /** Local registrar buffer full; a refusal before any database work, never a reliable receipt. */
    public AttemptAdmissionException registrarFull(){AttemptAdmissionException refusal=diagnostics.capacity("registrar_queue");diagnostics.record(refusal);return refusal;}
    /** The caller cancelled a still-QUEUED registration: it never reached MySQL. A refusal, never a receipt. */
    public AttemptAdmissionException registrarCancelled(){AttemptAdmissionException refusal=diagnostics.capacity("registrar_wait_cancelled");diagnostics.record(refusal);return refusal;}
    /**
     * Unlocked READ COMMITTED estimate for the advisory gate: {waiting, waitingLimit}. Uses the same capacity
     * and budget formula as {@link #register}; register itself remains the only authoritative check.
     */
    public int[] admissionEstimate(){
        Map<String,Object> settings=db.one("SELECT capacity,active_limit FROM tb_reservation_dispatch WHERE id=1");
        int waiting=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol<>4 AND state='WAITING'",Integer.class);
        int activeCount=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol<>4 AND state IN ('PROCESSING','CLAIMED')",Integer.class);
        long capacity=Db.number(settings,"capacity");
        int predicted=budget.waitingLimit((int)Db.number(settings,"active_limit"),activeCount);
        int limit=(int)Math.max(0,Math.min(predicted,capacity-activeCount));
        return new int[]{waiting,limit};
    }
    void localWorkers(java.util.function.IntSupplier workers){diagnostics.workers(workers);}
    public void completed(long nanos){budget.completed(nanos);}
    public Map<String,Object> register(ReserveRequest r,String ip,String fingerprint,long version){
        try{return AttemptTransactionTiming.execute(tx,metrics,"register",status->{
            Map<String,Object> settings=AttemptCoordinatorLock.acquire(db,metrics,"register");
            return registerLocked(settings,r,ip,fingerprint,version);
        });}catch(AttemptAdmissionException refusal){diagnostics.record(refusal);throw refusal;}
    }
    /** One registration request for {@link #registerBatch}; result or failure is filled in by the batch. */
    public static final class Registration {
        public final ReserveRequest request;public final String ip,fingerprint;public final long version;
        Map<String,Object> result;RuntimeException failure;
        public Registration(ReserveRequest request,String ip,String fingerprint,long version){this.request=request;this.ip=ip;this.fingerprint=fingerprint;this.version=version;}
        public Map<String,Object> result(){return result;}
        public RuntimeException failure(){return failure;}
    }
    /**
     * Registers several attempts under ONE coordinator lock and ONE commit. Every item runs the exact same
     * per-request rules as {@link #register} in arrival order, so each later item observes the rows inserted
     * by earlier items (same transaction). A business refusal or duplicate key of one item is rolled back to
     * that item's savepoint only. Any other database failure aborts the whole batch and is rethrown, because
     * MySQL may already have rolled back the entire transaction (deadlock) and no savepoint can be trusted.
     */
    public void registerBatch(List<Registration> items){
        if(items.isEmpty())return;
        metrics.summary("attempt.register.batch.size").record(items.size());
        AttemptTransactionTiming.execute(tx,metrics,"register_batch",status->{
            Map<String,Object> settings=AttemptCoordinatorLock.acquire(db,metrics,"register_batch");
            for(Registration item:items){
                Object savepoint=status.createSavepoint();
                try{item.result=registerLocked(settings,item.request,item.ip,item.fingerprint,item.version);item.failure=null;status.releaseSavepoint(savepoint);}
                catch(BusinessException|org.springframework.dao.DuplicateKeyException refused){status.rollbackToSavepoint(savepoint);item.result=null;item.failure=refused;}
            }
            return null;
        });
        for(Registration item:items)if(item.failure instanceof AttemptAdmissionException)diagnostics.record((AttemptAdmissionException)item.failure);
    }
    private Map<String,Object> registerLocked(Map<String,Object> settings,ReserveRequest r,String ip,String fingerprint,long version){
            Map<String,Object> previous=queue.byRequest(r.getRequestId());
            if(previous!=null){queue.match(previous,r,fingerprint);expireIfDueLocked(previous);return queue.get(previous.get("id").toString());}
            String owner=r.getUserId()+":"+r.getEventSessionId();
            Map<String,Object> active=db.one("SELECT * FROM tb_reservation_job WHERE active_owner=? FOR UPDATE",owner);
            if(active!=null){
                active=releaseFinishedOwnerLocked(active);
                if(active.get("active_owner")!=null){
                    queue.match(active,r,fingerprint);
                    if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_request_alias WHERE job_id=?",Long.class,active.get("id"))>=32)
                        throw diagnostics.capacity("alias_limit");
                    db.jdbc.update("INSERT INTO tb_reservation_request_alias(request_id,job_id) VALUES(?,?)",r.getRequestId(),active.get("id"));
                    return active;
                }
            }
            BusinessException.require(db.one("SELECT request_id FROM tb_reserve_request WHERE request_id=?",r.getRequestId())==null,"请求标识已用于已有交易");
            if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol=3 AND user_id=? AND state IN ('WAITING','PROCESSING')",Integer.class,r.getUserId())>=userLimit)
                throw diagnostics.capacity("user_inflight");
            int waiting=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol<>4 AND state='WAITING'",Integer.class);
            int activeCount=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol<>4 AND state IN ('PROCESSING','CLAIMED')",Integer.class);
            int predicted=budget.waitingLimit((int)Db.number(settings,"active_limit"),activeCount);
            diagnostics.observe(waiting,predicted,activeCount,(int)Db.number(settings,"capacity"));
            if(waiting+activeCount>=Db.number(settings,"capacity")||waiting>=predicted){
                throw diagnostics.capacity(waiting+activeCount>=Db.number(settings,"capacity")?"hard_capacity":"waiting_budget");
            }
            Timestamp accepted=time.get(),deadline=new Timestamp(accepted.getTime()+WAIT_MILLIS);String id=Db.id();
            db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,reservation_id,order_id,active_owner,attempt_protocol,waiting_deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'WAITING',?,?,?,?,3,?,?)",
                id,r.getRequestId(),fingerprint,r.getUserId(),r.getEventSessionId(),r.getTicketSkuId(),r.getQuantity(),r.getDeviceId(),ip,version,deadline,Db.id(),Db.id(),owner,deadline,accepted);
            if(scheduler!=null)scheduler.afterCommitWake();
            Map<String,Object> created=queue.get(id);created.put("newlyCreated",true);return created;
    }
    /**
     * Same-user/session ownership rule shared by protocol 3 registration and V3 start: an overdue WAITING row expires,
     * and a RESERVED row whose order reached a final state no longer owns the slot. Caller holds the row lock.
     */
    Map<String,Object> releaseFinishedOwnerLocked(Map<String,Object> active){
        expireIfDueLocked(active);active=queue.get(active.get("id").toString());
        if("RESERVED".equals(active.get("state"))){
            Map<String,Object> order=db.one("SELECT status FROM tb_ticket_order WHERE id=?",active.get("order_id"));
            if(order!=null&&Arrays.asList("PAID","ISSUED","PAY_TIMEOUT","CANCELLED","REFUNDED","RISK_BLOCKED").contains(order.get("status"))){
                db.jdbc.update("UPDATE tb_reservation_job SET active_owner=NULL WHERE id=?",active.get("id"));active=queue.get(active.get("id").toString());
            }
        }
        return active;
    }
    /** V3 capacity input: the same conservative worker/service-time prediction protocol 3 uses for its waiting budget. */
    int predictedWaitingLimit(int activeLimit,int processing){return budget.waitingLimit(activeLimit,processing);}
    boolean v3(){return v3;}
    private void expireIfDueLocked(Map<String,Object> job){
        if(isAttempt(job)&&"WAITING".equals(job.get("state"))&&((Timestamp)job.get("waiting_deadline")).getTime()<=time.get().getTime())expire(job);
    }
    private void expire(Map<String,Object> job){
        if(db.jdbc.update("UPDATE tb_reservation_job SET state='QUEUE_TIMEOUT',result_code='QUEUE_TIMEOUT',active_owner=NULL,lease_token=NULL,lease_until=NULL WHERE id=? AND state='WAITING'",job.get("id"))==1){
            queue.audit(job,"WAITING",null,"QUEUE_TIMEOUT",Collections.emptyMap());afterCommit(()->{metrics.counter("attempt.queue.timeout").increment();if(gate!=null)gate.leftWaiting();});
        }
    }
    /** A read can materialize overdue WAITING without depending on scheduler punctuality. */
    public Map<String,Object> refresh(String id){return tx.execute(status->{Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",id);if(job!=null)expireIfDueLocked(job);return queue.get(id);});}
    public void expireWaiting(){
        tx.executeWithoutResult(status->{
            Timestamp before=time.get();
            for(Map<String,Object> job:db.jdbc.queryForList("SELECT * FROM tb_reservation_job WHERE attempt_protocol=3 AND state='WAITING' AND waiting_deadline<=? ORDER BY waiting_deadline,id LIMIT 16"+skip,before))expireIfDueLocked(job);
        });
    }
    /** Non-owning bounded hint, so an empty queue does not launch four claim transactions per tick. */
    public int availableHint(){return availableHint(4);}
    public int availableHint(int limit){
        if(limit<1)return 0;
        Timestamp now=time.get();return db.jdbc.queryForList("SELECT id FROM tb_reservation_job WHERE attempt_protocol=3 AND ((state='WAITING' AND waiting_deadline>?) OR (state='PROCESSING' AND lease_until<=?)) LIMIT "+Math.min(limit,256),now,now).size();
    }
    /** Called inside an already-running worker, never before submitting work to a pool. */
    public Map<String,Object> claim(){
        if(readiness!=null&&!readiness.isReady())return null;
        return AttemptTransactionTiming.execute(tx,metrics,"claim",status->{
            Map<String,Object> settings=AttemptCoordinatorLock.acquire(db,metrics,"claim");
            return claimLocked(settings);
        });
    }
    /**
     * Grants up to {@code limit} attempts (stale-lease recoveries first) under ONE coordinator lock and ONE commit.
     * Each grant applies the same rules as {@link #claim}: fresh database time after the candidate lock, the
     * persisted global active_limit (re-counted after every grant, so earlier grants in this batch count),
     * and expiry instead of a late grant. The caller must already own one local worker slot per requested grant.
     */
    public List<Map<String,Object>> claimBatch(int limit){
        if(limit<1||readiness!=null&&!readiness.isReady())return Collections.emptyList();
        return AttemptTransactionTiming.execute(tx,metrics,"claim_batch",status->{
            Map<String,Object> settings=AttemptCoordinatorLock.acquire(db,metrics,"claim_batch");
            List<Map<String,Object>> granted=new ArrayList<>();
            while(granted.size()<limit){Map<String,Object> job=claimLocked(settings);if(job==null)break;granted.add(job);}
            return granted;
        });
    }
    private Map<String,Object> claimLocked(Map<String,Object> settings){
            Timestamp before=time.get();
            Map<String,Object> stale=db.one("SELECT * FROM tb_reservation_job WHERE attempt_protocol=3 AND state='PROCESSING' AND lease_until<=? ORDER BY lease_until,id LIMIT 1"+skip,before);
            if(stale!=null)return renew(stale,time.get(),false);
            if(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol<>4 AND state IN ('PROCESSING','CLAIMED')",Long.class)>=Db.number(settings,"active_limit"))return null;
            for(int scanned=0;scanned<16;scanned++){
                Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE attempt_protocol=3 AND state='WAITING' ORDER BY created_at,id LIMIT 1"+skip);
                if(job==null)return null;
                Timestamp now=time.get(); // Fresh AFTER acquiring the candidate row lock.
                if(((Timestamp)job.get("waiting_deadline")).getTime()<=now.getTime()){expire(job);continue;}
                return renew(job,now,true);
            }
            return null;
    }
    private Map<String,Object> renew(Map<String,Object> job,Timestamp now,boolean first){
        String token=Db.id();
        int changed=db.jdbc.update("UPDATE tb_reservation_job SET state='PROCESSING',lease_token=?,lease_until=?,processing_started_at=COALESCE(processing_started_at,?),claimed_at=? WHERE id=? AND state=?"+(first?" AND waiting_deadline>?":""),
            first?new Object[]{token,new Timestamp(now.getTime()+LEASE_MILLIS),now,now,job.get("id"),"WAITING",now}:new Object[]{token,new Timestamp(now.getTime()+LEASE_MILLIS),now,now,job.get("id"),"PROCESSING"});
        if(changed!=1)throw new IllegalStateException("Attempt claim lost its locked state");
        afterCommit(()->{
            metrics.counter("attempt.claim.granted","kind",first?"first":"recovery").increment();
            if(first)metrics.timer("attempt.waiting").record(Math.max(0,now.getTime()-((Timestamp)job.get("created_at")).getTime()),TimeUnit.MILLISECONDS);
            if(first&&gate!=null)gate.leftWaiting();
        });
        return queue.get(job.get("id").toString());
    }
    public boolean heartbeat(Map<String,Object> job){
        return tx.execute(status->db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id=? AND state='PROCESSING' AND lease_token=?",new Timestamp(time.get().getTime()+LEASE_MILLIS),job.get("id"),job.get("lease_token"))==1);
    }
    /** A later qualification relinquishes the worker, not its durable PROCESSING lifecycle. */
    public void defer(Map<String,Object> job){
        tx.executeWithoutResult(status->db.jdbc.update("UPDATE tb_reservation_job SET lease_token=NULL,lease_until=? WHERE id=? AND state='PROCESSING' AND lease_token=?",time.get(),job.get("id"),job.get("lease_token")));
    }
    public Map<String,Object> currentOwned(Map<String,Object> claimed){
        return tx.execute(status->{Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",claimed.get("id"));return queue.owns(job,claimed,"PROCESSING")?job:null;});
    }
    private static void afterCommit(Runnable effect){
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){effect.run();}});
    }
    /** Keep PROCESSING after risk; phase C will supply the per-SKU qualification order. */
    public Map<String,Object> decided(Map<String,Object> claimed,Map<String,Object> decision){
        return tx.execute(status->{
            Map<String,Object> current=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",claimed.get("id"));
            if(!queue.owns(current,claimed,"PROCESSING"))return null;
            if(current.get("admission")!=null)return current;
            String result=decision.get("decision").toString();boolean blocked="BLOCK".equals(result);
            if(!Arrays.asList("PASS","REVIEW","BLOCK").contains(result))throw new IllegalStateException("Invalid admission decision");
            queue.audit(current,"ENTRY",result,decision.get("resultCode").toString(),decision);
            db.jdbc.update("UPDATE tb_reservation_job SET decision=?,admission=?,result_code=?,state=?,active_owner=?,lease_token=?,lease_until=? WHERE id=?",result,db.json(decision),decision.get("resultCode"),blocked?"REJECTED":"PROCESSING",blocked?null:current.get("active_owner"),blocked?null:current.get("lease_token"),blocked?null:current.get("lease_until"),current.get("id"));
            return blocked?null:queue.get(current.get("id").toString());
        });
    }
}
