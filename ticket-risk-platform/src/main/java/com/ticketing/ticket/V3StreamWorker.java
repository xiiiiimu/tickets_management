package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import com.ticketing.infrastructure.TaskPools;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * V3 Stream consumer: replaces MySQL WAITING claims and the global coordinator lock.
 *
 * <p>Per message: START Lua (Redis decides start vs. queue timeout atomically, enforces the global active limit) →
 * protocol-4 row inserted/taken over in MySQL ({@link V3JobStarter}) → the unchanged {@code processAttempt} pipeline
 * (risk, prepare, ordered inventory Lua, HELD transaction, abort/compensation) → the MySQL outcome is copied to Redis and
 * only then the message is XACKed and XDELed in the same script. Anything not settled stays in the PEL and is re-driven
 * (own-PEL re-read, idle claim after the lease, or the orphan sweep for rows whose message is gone).
 */
@Component
@ConditionalOnProperty(name="ticketing.reservations-v3.enabled",havingValue="true")
public class V3StreamWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(V3StreamWorker.class);
    private final V3Redis redis;private final V3JobStarter starter;private final ReservationAttemptStore attempts;private final ReservationV2Service service;
    private final ReservationDispatch queue;private final TaskPools pools;private final Db db;private final MeterRegistry metrics;private final V3Entry entry;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptWorkerReadiness readiness;
    final String consumer;final int workers;final long leaseMillis,claimIdleMillis,orphanMillis;final boolean autoStart,autoOpen;
    /** requestId -> live work on this node (between START and settlement). */
    final ConcurrentMap<String,Running> running=new ConcurrentHashMap<>();
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private volatile boolean active;private volatile Thread loop;private ScheduledExecutorService timers;
    private volatile long lastOwnPending,lastClaim;
    // capacity estimator state (reconciler thread only)
    private long lastDone=-1,lastAt;private double drainPerSecond;
    static final class Running {final V3Redis.Message message;final String token;volatile Map<String,Object> job;Running(V3Redis.Message m,String t){message=m;token=t;}}

    public V3StreamWorker(V3Redis redis,V3JobStarter starter,ReservationAttemptStore attempts,ReservationV2Service service,ReservationDispatch queue,
                          TaskPools pools,Db db,MeterRegistry metrics,V3Entry entry,Environment env){
        this.redis=redis;this.starter=starter;this.attempts=attempts;this.service=service;this.queue=queue;this.pools=pools;this.db=db;this.metrics=metrics;this.entry=entry;
        consumer=env.getProperty("ticketing.reservations-v3.consumer",String.class,"node-"+Db.id().substring(0,8));
        workers=env.getProperty("ticketing.reservations-v3.workers",Integer.class,env.getProperty("ticketing.reservations-v2.attempts.workers",Integer.class,4));
        leaseMillis=ReservationAttemptStore.LEASE_MILLIS;
        claimIdleMillis=env.getProperty("ticketing.reservations-v3.claim-idle-ms",Long.class,leaseMillis);
        orphanMillis=env.getProperty("ticketing.reservations-v3.orphan-ms",Long.class,60000L);
        autoStart=env.getProperty("ticketing.reservations-v3.autostart",Boolean.class,true);
        autoOpen=env.getProperty("ticketing.reservations-v3.auto-open",Boolean.class,true);
        int poolMax=env.getProperty("ticketing.pools.sale.max",Integer.class,4);
        if(workers<1||workers>256)throw new IllegalArgumentException("reservations-v3.workers must be 1..256");
        if(poolMax<workers)throw new IllegalStateException("ticketing.pools.sale.max must be >= reservations-v3.workers");
        if(claimIdleMillis<leaseMillis)throw new IllegalArgumentException("claim-idle-ms must not be shorter than the 15s lease");
        if(orphanMillis<claimIdleMillis*2)throw new IllegalArgumentException("orphan-ms must exceed twice the claim idle time");
        // Mutual exclusion with protocol-3 components that would admit or dispatch work of their own.
        for(String off:Arrays.asList("ticketing.reservations-v2.attempts.batch-register","ticketing.reservations-v2.attempts.redis-gate","ticketing.reservations-v2.attempts.batch-claim"))
            if(env.getProperty(off,Boolean.class,false))throw new IllegalStateException(off+" must be off with V3");
        if(!env.getProperty("ticketing.reservations-v2.attempts.enabled",Boolean.class,false))throw new IllegalStateException("V3 reuses the durable attempt pipeline: ticketing.reservations-v2.attempts.enabled=true");
        Gauge.builder("attempt.v3.inflight",inFlight,Set::size).register(metrics);
    }

    @PostConstruct public void init(){
        if(!attempts.v3())throw new IllegalStateException("ReservationAttemptStore is not in V3 mode");
        redis.ensureGroup();
        // First activation opens acceptance. An explicit mode (OPEN/CLOSED, set by an operator for drain/rollback) is kept.
        if(autoOpen&&redis.mode()==null)redis.mode("OPEN");
        timers=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"v3-timers");t.setDaemon(true);return t;});
        timers.scheduleWithFixedDelay(()->safely("heartbeat",this::heartbeat),5,5,TimeUnit.SECONDS);
        timers.scheduleWithFixedDelay(()->safely("capacity",this::publishCapacity),0,500,TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(()->safely("orphans",this::sweepOrphans),orphanMillis,Math.max(1000,orphanMillis/6),TimeUnit.MILLISECONDS);
        if(autoStart)start();
    }
    public synchronized void start(){
        if(active)return;active=true;
        Thread t=new Thread(this::run,"v3-consumer");t.setDaemon(true);loop=t;t.start();
    }
    /** Stops pulling new messages; running work finishes or stays in the PEL for another consumer. */
    @PreDestroy public synchronized void stop(){
        active=false;Thread t=loop;if(t!=null){t.interrupt();try{t.join(2000);}catch(InterruptedException i){Thread.currentThread().interrupt();}}
        if(timers!=null)timers.shutdownNow();
    }
    private void safely(String what,Runnable task){try{task.run();}catch(RuntimeException failure){metrics.counter("attempt.v3.background.failed","task",what).increment();log.warn("V3 {} deferred",what,failure);}}

    private void run(){
        while(active){
            try{
                if(readiness!=null&&!readiness.ensureReady()){Thread.sleep(200);continue;}
                int free=workers-inFlight.size();
                if(free<=0){Thread.sleep(5);continue;}
                if(pollOnce(free)==0)Thread.sleep(10);
            }catch(InterruptedException stop){return;}
            catch(RuntimeException unavailable){metrics.counter("attempt.v3.poll.failed").increment();try{Thread.sleep(200);}catch(InterruptedException stop){return;}}
        }
    }
    /** One polling round; package-private so tests can drive the consumer deterministically. */
    int pollOnce(int free){
        List<MapRecord<String,Object,Object>> batch=new ArrayList<>(redis.readNew(consumer,free));
        long now=System.currentTimeMillis();
        if(batch.size()<free&&now-lastOwnPending>=500){lastOwnPending=now;for(MapRecord<String,Object,Object> r:redis.readOwnPending(consumer,free*2))if(batch.size()<free)batch.add(r);}
        if(batch.size()<free&&now-lastClaim>=1000){lastClaim=now;batch.addAll(redis.claimIdle(consumer,Duration.ofMillis(claimIdleMillis),free-batch.size()));}
        int submitted=0;
        for(MapRecord<String,Object,Object> record:batch){
            String requestId=String.valueOf(record.getValue().get("requestId"));
            if(!inFlight.add(requestId))continue;
            try{pools.execute("sale",()->{try{handle(record);}finally{inFlight.remove(requestId);}});submitted++;}
            catch(RejectedExecutionException full){inFlight.remove(requestId);metrics.counter("attempt.worker.rejected").increment();break;}
        }
        return submitted;
    }
    void handle(MapRecord<String,Object,Object> record){
        long begin=System.nanoTime();String outcome="error";
        try{
            V3Redis.Message m;
            try{m=new V3Redis.Message(record.getId().getValue(),record.getValue());}
            catch(RuntimeException malformed){log.error("V3 malformed message id={} dropped",record.getId(),malformed);redis.ackDelete(record.getId().getValue());outcome="malformed";return;}
            String token=Db.id();
            long received=System.currentTimeMillis();
            List<String> decision=AttemptPath.time(metrics,"v3_start_lua",()->redis.start(m,token,leaseMillis,workers));
            outcome=decision.isEmpty()?"empty":decision.get(0).toLowerCase(Locale.ROOT);
            // Stream queue time: Redis acceptance -> first START decision (same host clocks in the benchmark).
            if("start".equals(outcome)||"timeout".equals(outcome))AttemptPath.record(metrics,"v3_stream_wait",outcome,TimeUnit.MILLISECONDS.toNanos(Math.max(0,received-m.acceptedAt)));
            switch(outcome){
                case "start":case "takeover":startOwned(m,token);break;
                case "timeout":timeout(m);break;
                case "settled":redis.ackDelete(m.streamId);break;
                default:break; // full / held_by_other: stays in the PEL, retried later
            }
        }catch(RuntimeException unavailable){
            metrics.counter("attempt.v3.handle.failed").increment();
            log.warn("V3 message kept for retry id={}",record.getId(),unavailable);
        }finally{metrics.timer("attempt.v3.handle","outcome",outcome).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
    private void startOwned(V3Redis.Message m,String token){
        V3JobStarter.Outcome o=AttemptPath.time(metrics,"v3_t0_begin",()->starter.begin(m,token));
        switch(o.kind){
            case OWNED:{
                Running work=new Running(m,token);work.job=o.job;running.put(m.requestId,work);
                try{AttemptPath.run(metrics,"v3_process",()->service.processV3(o.job,true));}finally{running.remove(m.requestId,work);}
                AttemptPath.run(metrics,"v3_settle",()->settleFromMysql(m,token,String.valueOf(o.job.get("id"))));
                AttemptPath.record(metrics,"v3_accept_to_settled","ok",TimeUnit.MILLISECONDS.toNanos(Math.max(0,System.currentTimeMillis()-m.acceptedAt)));break;
            }
            case TERMINAL:redis.settle(m.requestId,m.userId,m.sessionId,"DONE",o.state,m.streamId,token,null);break;
            case ALIAS:redis.settle(m.requestId,m.userId,m.sessionId,"ALIAS","ALIAS",m.streamId,token,String.valueOf(o.job.get("request_id")));break;
            case CONFLICT:redis.settle(m.requestId,m.userId,m.sessionId,"CONFLICT",o.state,m.streamId,token,null);break;
            default:break; // HELD_BY_OTHER: another holder of the MySQL lease will settle; our copy waits
        }
    }
    /** Settle only from the committed MySQL state; a still-PROCESSING row keeps its message for re-drive. */
    private void settleFromMysql(V3Redis.Message m,String token,String jobId){
        Map<String,Object> job=queue.get(jobId);
        String state=job==null?null:String.valueOf(job.get("state"));
        if(job==null||V3JobStarter.ONGOING.contains(state)){metrics.counter("attempt.v3.unsettled").increment();return;}
        if(!redis.settle(m.requestId,m.userId,m.sessionId,"DONE",state,m.streamId,token,null))metrics.counter("attempt.v3.settle.stale_token").increment();
    }
    private void timeout(V3Redis.Message m){
        V3JobStarter.Outcome o=AttemptPath.time(metrics,"v3_timeout_fence",()->starter.fenceTimeout(m));
        switch(o.kind){
            case FENCED:case TERMINAL:redis.settle(m.requestId,m.userId,m.sessionId,"DONE",o.state,m.streamId,"",null);break;
            case DELEGATED:redis.settle(m.requestId,m.userId,m.sessionId,"MYSQL",o.state,m.streamId,"",null);break; // a start already committed: never report failure
            case ALIAS:redis.settle(m.requestId,m.userId,m.sessionId,"ALIAS","ALIAS",m.streamId,"",String.valueOf(o.job.get("request_id")));break;
            case CONFLICT:redis.settle(m.requestId,m.userId,m.sessionId,"CONFLICT",o.state,m.streamId,"",null);break;
            default:break;
        }
    }

    /** MySQL lease, Redis lease and PEL idle time are renewed together for live work. */
    void heartbeat(){
        List<String> ids=new ArrayList<>();
        for(Running w:running.values()){
            Map<String,Object> job=w.job;
            try{
                if(job!=null&&!attempts.heartbeat(job))continue; // lost the MySQL fence: do not keep the message alive
                redis.renew(w.message.requestId,w.message.userId,w.token,leaseMillis);ids.add(w.message.streamId);
            }catch(RuntimeException unavailable){metrics.counter("attempt.v3.heartbeat.failed").increment();}
        }
        redis.touch(consumer,ids);
    }

    /**
     * Acceptance capacity = min(worker/service-time prediction, observed drain over ~4s while saturated, hard capacity).
     * Waiting entries expire at their own deadline and stale active entries are trimmed, so no slot can leak forever.
     */
    void publishCapacity(){
        Map<String,Object> settings=db.one("SELECT capacity,active_limit FROM tb_reservation_dispatch WHERE id=1");
        int activeLimit=(int)Db.number(settings,"active_limit"),hard=(int)Db.number(settings,"capacity");
        entry.fallbackCap=Math.max(1,activeLimit);
        long[] seen=redis.publishCapacity(lastCap(activeLimit,hard),activeLimit,leaseMillis*2);
        long now=seen[0],activeNow=seen[1],waitingNow=seen[2],done=seen[3];
        if(lastDone>=0&&now>lastAt){
            double rate=(done-lastDone)*1000d/(now-lastAt);
            // Only a saturated interval (work waiting) measures capacity; an idle one only measures demand.
            if(waitingNow>0)drainPerSecond=drainPerSecond==0?rate:drainPerSecond*0.7+rate*0.3;
        }
        lastDone=done;lastAt=now;lastActive=(int)activeNow;
    }
    private int lastActive;
    int lastCap(int activeLimit,int hard){
        int formula=attempts.predictedWaitingLimit(activeLimit,lastActive);
        int rateCap=drainPerSecond>0?(int)Math.ceil(drainPerSecond*4.0)+activeLimit:formula;
        int cap=Math.min(Math.min(formula,rateCap),hard-lastActive);
        return Math.max(Math.min(activeLimit,Math.max(0,hard-lastActive)),cap);
    }

    /** Protocol-4 rows PROCESSING with a lease expired long ago and no live message: re-drive them on this node. */
    void sweepOrphans(){
        List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT id,request_id FROM tb_reservation_job WHERE attempt_protocol=4 AND state='PROCESSING' AND (lease_until IS NULL OR lease_until<?) LIMIT 8",
            new java.sql.Timestamp(db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).getTime()-orphanMillis));
        for(Map<String,Object> row:rows){
            String requestId=row.get("request_id").toString();
            if(!inFlight.add(requestId))continue;
            try{pools.execute("sale",()->{try{redriveOrphan(row.get("id").toString());}finally{inFlight.remove(requestId);}});}
            catch(RejectedExecutionException full){inFlight.remove(requestId);return;}
        }
    }
    void redriveOrphan(String jobId){
        String token=Db.id();V3JobStarter.Outcome o=starter.takeOverOrphan(jobId,token,orphanMillis);
        if(o.kind!=V3JobStarter.Kind.OWNED)return;
        metrics.counter("attempt.v3.orphan.redriven").increment();
        service.processV3(o.job,true);
        Map<String,Object> job=queue.get(jobId);
        if(job!=null&&!V3JobStarter.ONGOING.contains(String.valueOf(job.get("state"))))
            redis.settle(job.get("request_id").toString(),String.valueOf(job.get("user_id")),job.get("session_id").toString(),"DONE",String.valueOf(job.get("state")),"","",null);
    }
}
