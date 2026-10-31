package com.ticketing.ticket;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Advisory Redis admission gate in front of the durable attempt registration.
 *
 * <p>Purpose: refuse obvious overload with 429 <b>before</b> any MySQL work, so the database only sees
 * requests that can plausibly be registered. It is never authoritative: {@link ReservationAttemptStore#register}
 * still re-checks capacity, waiting budget, user in-flight and idempotency under the coordinator lock.
 *
 * <p>Gate occupancy = {@code waiting + |held| + |fresh|}:
 * <ul>
 *   <li>{@code waiting} (hash field): the committed WAITING count published from MySQL by the reconciler, decremented
 *       best-effort when an attempt leaves WAITING.</li>
 *   <li>{@code held} (zset, member {@code requestId|token}, score = expiry): one entry per admission that is not yet
 *       known to MySQL. The reconciler never touches it, so a MySQL COUNT can not wipe uncommitted reservations.
 *       Only the admission that created an entry removes it, by its exact member ({@link Hold}), idempotently;
 *       an entry whose outcome stays unknown expires after {@code redis-gate-hold-ms} (conservative: it may be
 *       counted twice meanwhile, never zero times).</li>
 *   <li>{@code fresh} (zset, member requestId, score = Redis time of the commit notice): rows committed after the
 *       last published count. A reconcile removes only entries noticed before it read Redis time, i.e. rows its
 *       MySQL count is guaranteed to include.</li>
 * </ul>
 * A requestId already admitted (marker key present) always passes, so idempotent retries reach MySQL and get their
 * original receipt. Missing gate state or any Redis failure means PASS-THROUGH, never a correctness change.
 * All keys share the {attempt-gate} hash tag so the scripts stay single-slot; all times are Redis TIME.
 */
@Component
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled","attempts.redis-gate"},havingValue="true")
public class AttemptAdmissionGate {
    static final String STATE="{attempt-gate}:state",MARKER="{attempt-gate}:req:",HELD="{attempt-gate}:held",FRESH="{attempt-gate}:fresh";
    private static final String NOW="if redis.replicate_commands then pcall(redis.replicate_commands) end "
       +"local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000) ";
    /** 0 refuse, 1 known requestId, 2 no state (pass-through), 3 admitted (held entry created). */
    private static final DefaultRedisScript<Long> ADMIT=new DefaultRedisScript<>(
        "if redis.call('EXISTS',KEYS[2])==1 then return 1 end "
       +"local limit=redis.call('HGET',KEYS[1],'limit') "
       +"if not limit then return 2 end "
       +NOW
       +"redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now) "
       +"redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now-tonumber(ARGV[4])) "
       +"local used=tonumber(redis.call('HGET',KEYS[1],'waiting') or '0')+redis.call('ZCARD',KEYS[3])+redis.call('ZCARD',KEYS[4]) "
       +"if used>=tonumber(limit) then return 0 end "
       +"redis.call('ZADD',KEYS[3],now+tonumber(ARGV[2]),ARGV[3]) "
       +"redis.call('PEXPIRE',KEYS[3],tonumber(ARGV[2])*2) "
       +"redis.call('SET',KEYS[2],ARGV[5],'PX',ARGV[1]) "
       +"return 3",Long.class);
    /** Definitely not registered: drop exactly this admission's entry; drop the marker only if it is still ours. */
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "local removed=redis.call('ZREM',KEYS[1],ARGV[1]) "
       +"if redis.call('GET',KEYS[2])==ARGV[2] then redis.call('DEL',KEYS[2]) end "
       +"return removed",Long.class);
    /** Committed: drop this admission's held entry; a new WAITING row is counted in fresh until a reconcile sees it. */
    private static final DefaultRedisScript<Long> COMMITTED=new DefaultRedisScript<>(
        "if ARGV[1]~='' then redis.call('ZREM',KEYS[1],ARGV[1]) end "
       +"if ARGV[2]=='1' then "+NOW
       +"redis.call('ZADD',KEYS[2],now,ARGV[3]) redis.call('PEXPIRE',KEYS[2],tonumber(ARGV[4])) end "
       +"return 1",Long.class);
    private static final DefaultRedisScript<Long> LEFT=new DefaultRedisScript<>(
        "if redis.call('EXISTS',KEYS[1])==0 then return 0 end "
       +"local waiting=tonumber(redis.call('HGET',KEYS[1],'waiting') or '0') "
       +"if waiting>0 then redis.call('HINCRBY',KEYS[1],'waiting',-1) end "
       +"return 1",Long.class);
    private static final DefaultRedisScript<Long> CLOCK=new DefaultRedisScript<>(NOW+"return now",Long.class);
    /** Publishes the MySQL count read after Redis time ARGV[4]; fresh rows noticed before that are in the count. */
    private static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>(
        "redis.call('HSET',KEYS[1],'waiting',ARGV[1],'limit',ARGV[2]) "
       +"redis.call('PEXPIRE',KEYS[1],ARGV[3]) "
       +"redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf','('..ARGV[4]) "
       +"return 1",Long.class);

    /**
     * One admission decision. {@link #committed} / {@link #notRegistered} are idempotent and act at most once in
     * total; they only ever touch this admission's own held entry and marker token.
     */
    public static final class Hold implements AttemptRegistrar.Settlement {
        public final String requestId;final String token;final boolean refused,reserved;private final AttemptAdmissionGate gate;
        private final java.util.concurrent.atomic.AtomicBoolean settled=new java.util.concurrent.atomic.AtomicBoolean();
        Hold(AttemptAdmissionGate gate,String requestId,String token,boolean refused,boolean reserved){this.gate=gate;this.requestId=requestId;this.token=token;this.refused=refused;this.reserved=reserved;}
        public boolean refused(){return refused;}
        public boolean reserved(){return reserved;}
        @Override public void committed(boolean newWaiting){if(!refused&&settled.compareAndSet(false,true))gate.settleCommitted(this,newWaiting);}
        @Override public void notRegistered(){if(!refused&&settled.compareAndSet(false,true)&&reserved)gate.release(this);}
        String member(){return requestId+"|"+token;}
    }

    private final StringRedisTemplate redis;private final ObjectProvider<ReservationAttemptStore> stores;private final MeterRegistry metrics;
    private final long markerMillis,reconcileMillis,stateTtlMillis,holdMillis;
    private volatile ScheduledExecutorService reconciler;

    public AttemptAdmissionGate(StringRedisTemplate redis,ObjectProvider<ReservationAttemptStore> stores,MeterRegistry metrics,Environment env){
        this.redis=redis;this.stores=stores;this.metrics=metrics;
        markerMillis=env.getProperty("ticketing.reservations-v2.attempts.redis-gate-marker-ms",Long.class,600000L);
        reconcileMillis=env.getProperty("ticketing.reservations-v2.attempts.redis-gate-reconcile-ms",Long.class,200L);
        holdMillis=env.getProperty("ticketing.reservations-v2.attempts.redis-gate-hold-ms",Long.class,10000L);
        if(markerMillis<10000||markerMillis>86400000L)throw new IllegalArgumentException("redis-gate-marker-ms must be 10s..24h");
        if(reconcileMillis<20||reconcileMillis>5000)throw new IllegalArgumentException("redis-gate-reconcile-ms must be 20..5000");
        if(holdMillis<1000||holdMillis>600000)throw new IllegalArgumentException("redis-gate-hold-ms must be 1s..10min");
        // A held entry must outlive the longest time its caller can still be waiting for the database.
        long callerWait=Boolean.TRUE.equals(env.getProperty("ticketing.reservations-v2.attempts.batch-register",Boolean.class,false))
            ?env.getProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms",Long.class,1000L)+env.getProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms",Long.class,4000L)
            :3000L;
        if(holdMillis<=callerWait+1000)throw new IllegalArgumentException("redis-gate-hold-ms must exceed the registration caller wait ("+callerWait+"ms) by more than 1s");
        // If every node stops publishing, the state expires and the gate becomes pass-through.
        stateTtlMillis=Math.max(2000,reconcileMillis*10);
    }

    @PostConstruct public void start(){
        ScheduledExecutorService e=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"attempt-gate-reconcile");t.setDaemon(true);return t;});
        reconciler=e;e.scheduleWithFixedDelay(this::reconcileSafely,0,reconcileMillis,TimeUnit.MILLISECONDS);
    }
    @PreDestroy public void stop(){ScheduledExecutorService e=reconciler;if(e!=null)e.shutdownNow();}

    /** {@link Hold#refused()} only when the gate positively refuses; every uncertainty passes through to MySQL. */
    public Hold admit(String requestId){
        String token=java.util.UUID.randomUUID().toString();
        try{
            Long result=redis.execute(ADMIT,Arrays.asList(STATE,MARKER+requestId,HELD,FRESH),
                Long.toString(markerMillis),Long.toString(holdMillis),requestId+"|"+token,Long.toString(stateTtlMillis),token);
            String outcome=result==null?"pass_through":result==0?"refused":result==1?"known":result==2?"pass_through":"admitted";
            metrics.counter("attempt.gate.decision","outcome",outcome).increment();
            return new Hold(this,requestId,token,result!=null&&result==0,result!=null&&result==3);
        }catch(RuntimeException unavailable){
            metrics.counter("attempt.gate.decision","outcome","redis_error").increment();
            return new Hold(this,requestId,token,false,false);
        }
    }
    void release(Hold hold){
        try{redis.execute(RELEASE,Arrays.asList(HELD,MARKER+hold.requestId),hold.member(),hold.token);metrics.counter("attempt.gate.settle","outcome","released").increment();}
        catch(RuntimeException ignored){metrics.counter("attempt.gate.settle.failed").increment();}
    }
    void settleCommitted(Hold hold,boolean newWaiting){
        if(!hold.reserved&&!newWaiting)return;
        try{redis.execute(COMMITTED,Arrays.asList(HELD,FRESH),hold.reserved?hold.member():"",newWaiting?"1":"0",hold.requestId,Long.toString(stateTtlMillis));
            metrics.counter("attempt.gate.settle","outcome",newWaiting?"committed_waiting":"committed_existing").increment();}
        catch(RuntimeException ignored){metrics.counter("attempt.gate.settle.failed").increment();}
    }
    /** Best-effort; called after a WAITING attempt was committed as PROCESSING or QUEUE_TIMEOUT. */
    public void leftWaiting(){
        try{redis.execute(LEFT,Collections.singletonList(STATE));}catch(RuntimeException ignored){metrics.counter("attempt.gate.left.failed").increment();}
    }
    void reconcileSafely(){
        try{reconcile();}catch(RuntimeException failure){metrics.counter("attempt.gate.reconcile.failed").increment();}
    }
    /**
     * Overwrites the committed part ({@code waiting}, {@code limit}) with the MySQL estimate. Held entries are left
     * alone. Redis time is read BEFORE the MySQL count, so only fresh rows noticed earlier are dropped.
     */
    void reconcile(){
        ReservationAttemptStore store=stores.getIfAvailable();if(store==null)return;
        Long before=redis.execute(CLOCK,Collections.singletonList(STATE));
        int[] estimate=store.admissionEstimate();
        redis.execute(PUBLISH,Arrays.asList(STATE,FRESH),Integer.toString(estimate[0]),Integer.toString(estimate[1]),Long.toString(stateTtlMillis),Long.toString(before==null?0:before));
        metrics.counter("attempt.gate.reconcile").increment();
    }
}
