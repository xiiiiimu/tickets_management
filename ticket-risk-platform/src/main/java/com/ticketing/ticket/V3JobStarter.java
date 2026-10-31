package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;

/**
 * MySQL side of V3: the protocol-4 job row is created when a Stream consumer starts the request ("insert on start"),
 * never by the HTTP request. {@code tb_reservation_job.request_id UNIQUE} makes "started" and "queue timeout" mutually
 * exclusive across every Redis lifetime: the first committed row wins and a terminal row never becomes PROCESSING again.
 * No global lock: only the request's own row / unique index entries.
 */
@Component
@ConditionalOnProperty(name="ticketing.reservations-v3.enabled",havingValue="true")
public class V3JobStarter {
    public enum Kind {OWNED,TERMINAL,HELD_BY_OTHER,ALIAS,CONFLICT,FENCED,DELEGATED}
    public static final class Outcome {
        public final Kind kind;public final Map<String,Object> job;public final String state;
        Outcome(Kind kind,Map<String,Object> job,String state){this.kind=kind;this.job=job;this.state=state;}
        public String toString(){return kind+(state==null?"":":"+state);}
    }
    static final List<String> ONGOING=Arrays.asList("WAITING","PROCESSING","EVALUATING","QUEUED","CLAIMED");
    private final Db db;private final ReservationDispatch queue;private final ReservationAttemptStore store;private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    public V3JobStarter(Db db,ReservationDispatch queue,ReservationAttemptStore store,PlatformTransactionManager manager,MeterRegistry metrics){
        this.db=db;this.queue=queue;this.store=store;this.metrics=metrics;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(3);
    }
    private Timestamp now(){return db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class);}
    private static boolean same(Map<String,Object> job,V3Redis.Message m){
        return Objects.equals(String.valueOf(job.get("user_id")),m.userId)&&Objects.equals(job.get("fingerprint"),m.fingerprint);
    }

    /** Start (or take over) the request under {@code token}. Concurrent inserts of the same request/owner are retried. */
    public Outcome begin(V3Redis.Message m,String token){
        long begin=System.nanoTime();String outcome="failure";
        try{
        for(int attempt=0;;attempt++){
            try{Outcome o=AttemptTransactionTiming.execute(tx,metrics,"v3_begin",status->{observeCommit();return beginLocked(m,token);});metrics.counter("attempt.v3.start.mysql","outcome",o.kind.name().toLowerCase(Locale.ROOT)).increment();outcome="success";return o;}
            catch(DuplicateKeyException race){if(attempt>=2)throw race;}
        }
        }finally{metrics.timer("attempt.v3.begin","outcome",outcome).record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    /** JDBC wall time includes server locks/network/decode; it is NOT pure server SQL execution. */
    private <T>T beginSql(String operation,java.util.function.Supplier<T> action){
        long start=System.nanoTime();String outcome="failure";
        try{T result=action.get();outcome="success";return result;}
        finally{metrics.timer("attempt.v3.begin.sql","operation",operation,"outcome",outcome).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    private Timestamp beginNow(){return beginSql("database_time",this::now);}
    private Map<String,Object> beginJob(String id){return beginSql("read_job",()->queue.get(id));}
    /** Callback interval includes transaction callbacks; never label it fsync or connection acquisition. */
    private void observeCommit(){
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            private long start;
            @Override public void beforeCommit(boolean readOnly){start=System.nanoTime();}
            @Override public void afterCompletion(int status){if(start!=0)metrics.timer("attempt.v3.begin.commit_callbacks","outcome",status==STATUS_COMMITTED?"success":"failure").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
        });
    }
    private Outcome beginLocked(V3Redis.Message m,String token){
        Map<String,Object> existing=beginSql("lock_request",()->db.one("SELECT * FROM tb_reservation_job WHERE request_id=? FOR UPDATE",m.requestId));
        if(existing!=null)return resume(existing,m,token);
        Map<String,Object> alias=beginSql("lookup_alias",()->db.one("SELECT job_id FROM tb_reservation_request_alias WHERE request_id=?",m.requestId));
        if(alias!=null)return new Outcome(Kind.ALIAS,beginJob(alias.get("job_id").toString()),null);
        // A request id already used by another (legacy) trade: never start a second one under the same id.
        if(beginSql("lookup_legacy_intent",()->db.one("SELECT request_id FROM tb_reserve_request WHERE request_id=?",m.requestId))!=null)return new Outcome(Kind.CONFLICT,null,"REQUEST_ID_IN_USE");
        String owner=m.userId+":"+m.sessionId;
        Map<String,Object> active=beginSql("lock_active_owner",()->db.one("SELECT * FROM tb_reservation_job WHERE active_owner=? FOR UPDATE",owner));
        if(active!=null){
            final Map<String,Object> found=active;active=beginSql("release_finished_owner",()->store.releaseFinishedOwnerLocked(found));
            if(active.get("active_owner")!=null){
                // Same user+session already has an attempt (Redis owner marker lost): alias it, exactly like protocol 3.
                if(!same(active,m))return new Outcome(Kind.CONFLICT,active,"OWNER_PARAMETERS_DIFFER");
                final Map<String,Object> target=active;
                if(beginSql("count_aliases",()->db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_request_alias WHERE job_id=?",Long.class,target.get("id")))>=32)
                    return new Outcome(Kind.CONFLICT,active,"ALIAS_LIMIT");
                beginSql("insert_alias",()->db.jdbc.update("INSERT INTO tb_reservation_request_alias(request_id,job_id) VALUES(?,?)",m.requestId,target.get("id")));
                return new Outcome(Kind.ALIAS,active,null);
            }
        }
        Timestamp now=beginNow(),deadline=new Timestamp(m.deadline),accepted=new Timestamp(m.acceptedAt);
        beginSql("insert_job",()->db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,"
                +"lease_token,lease_until,claimed_at,processing_started_at,reservation_id,order_id,active_owner,attempt_protocol,waiting_deadline,created_at) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,'PROCESSING',?,?,?,?,?,?,?,?,4,?,?)",
            m.attemptId,m.requestId,m.fingerprint,Long.parseLong(m.userId),m.sessionId,m.skuId,m.quantity,m.deviceId,m.ip,m.saleVersion,deadline,
            token,new Timestamp(now.getTime()+ReservationAttemptStore.LEASE_MILLIS),now,now,m.reservationId,m.orderId,owner,deadline,accepted));
        return new Outcome(Kind.OWNED,beginJob(m.attemptId),"PROCESSING");
    }
    private Outcome resume(Map<String,Object> existing,V3Redis.Message m,String token){
        if(!same(existing,m))return new Outcome(Kind.CONFLICT,existing,"REQUEST_PARAMETERS_DIFFER");
        String state=String.valueOf(existing.get("state"));
        if(!ReservationAttemptStore.isV3(existing))return ONGOING.contains(state)?new Outcome(Kind.HELD_BY_OTHER,existing,state):new Outcome(Kind.TERMINAL,existing,state);
        if(!"PROCESSING".equals(state))return new Outcome(Kind.TERMINAL,existing,state);
        Timestamp now=beginNow(),lease=(Timestamp)existing.get("lease_until");
        if(existing.get("lease_token")!=null&&lease!=null&&lease.after(now)&&!token.equals(existing.get("lease_token")))return new Outcome(Kind.HELD_BY_OTHER,existing,state);
        // Takeover rotates the MySQL fence: every later transaction of the previous holder fails lockOwned.
        int changed=beginSql("takeover",()->db.jdbc.update("UPDATE tb_reservation_job SET lease_token=?,lease_until=?,claimed_at=? WHERE id=? AND state='PROCESSING'",
            token,new Timestamp(now.getTime()+ReservationAttemptStore.LEASE_MILLIS),now,existing.get("id")));
        if(changed!=1)throw new IllegalStateException("V3 takeover lost its row");
        metrics.counter("attempt.v3.takeover").increment();
        return new Outcome(Kind.OWNED,beginJob(existing.get("id").toString()),"PROCESSING");
    }
    /** Orphan recovery (row PROCESSING, no live message): take over a lease that expired long ago. */
    public Outcome takeOverOrphan(String jobId,String token,long minExpiredMillis){
        return tx.execute(status->{
            Map<String,Object> job=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",jobId);
            if(job==null||!ReservationAttemptStore.isV3(job)||!"PROCESSING".equals(job.get("state")))return new Outcome(Kind.TERMINAL,job,job==null?null:String.valueOf(job.get("state")));
            Timestamp now=now(),lease=(Timestamp)job.get("lease_until");
            if(lease!=null&&lease.getTime()>now.getTime()-minExpiredMillis)return new Outcome(Kind.HELD_BY_OTHER,job,"PROCESSING");
            db.jdbc.update("UPDATE tb_reservation_job SET lease_token=?,lease_until=?,claimed_at=? WHERE id=? AND state='PROCESSING'",token,new Timestamp(now.getTime()+ReservationAttemptStore.LEASE_MILLIS),now,jobId);
            metrics.counter("attempt.v3.orphan.takeover").increment();
            return new Outcome(Kind.OWNED,queue.get(jobId),"PROCESSING");
        });
    }

    /**
     * Durable queue-timeout terminal state (the only failure decided before a job row exists). After this commits, no
     * later start of the same request id can succeed. If a start already committed (another Redis lifetime), the fence is
     * not written and the caller must not report failure.
     */
    public Outcome fenceTimeout(V3Redis.Message m){
        for(int attempt=0;;attempt++){
            try{Outcome o=tx.execute(status->fenceLocked(m));metrics.counter("attempt.v3.timeout.fence","outcome",o.kind.name().toLowerCase(Locale.ROOT)).increment();return o;}
            catch(DuplicateKeyException race){if(attempt>=2)throw race;}
        }
    }
    private Outcome fenceLocked(V3Redis.Message m){
        Map<String,Object> existing=db.one("SELECT * FROM tb_reservation_job WHERE request_id=? FOR UPDATE",m.requestId);
        if(existing!=null){
            String state=String.valueOf(existing.get("state"));
            return ONGOING.contains(state)?new Outcome(Kind.DELEGATED,existing,state):new Outcome(Kind.TERMINAL,existing,state);
        }
        Map<String,Object> alias=db.one("SELECT job_id FROM tb_reservation_request_alias WHERE request_id=?",m.requestId);
        if(alias!=null)return new Outcome(Kind.ALIAS,queue.get(alias.get("job_id").toString()),null);
        if(db.one("SELECT request_id FROM tb_reserve_request WHERE request_id=?",m.requestId)!=null)return new Outcome(Kind.CONFLICT,null,"REQUEST_ID_IN_USE");
        Timestamp deadline=new Timestamp(m.deadline);
        db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,result_code,queue_expires_at,"
                +"reservation_id,order_id,active_owner,attempt_protocol,waiting_deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUE_TIMEOUT','QUEUE_TIMEOUT',?,?,?,NULL,4,?,?)",
            m.attemptId,m.requestId,m.fingerprint,Long.parseLong(m.userId),m.sessionId,m.skuId,m.quantity,m.deviceId,m.ip,m.saleVersion,deadline,
            m.reservationId,m.orderId,deadline,new Timestamp(m.acceptedAt));
        queue.audit(queue.get(m.attemptId),"WAITING",null,"QUEUE_TIMEOUT",Collections.singletonMap("protocol",4));
        return new Outcome(Kind.FENCED,queue.get(m.attemptId),"QUEUE_TIMEOUT");
    }
}
