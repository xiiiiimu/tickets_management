package com.ticketing.ticket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Groups concurrent HTTP registrations into one {@link ReservationAttemptStore#registerBatch} transaction:
 * one coordinator-row lock and one commit per batch instead of per request.
 *
 * <p>Every queued request has one atomic state: {@code QUEUED -> TAKEN} (the registrar thread took it into a batch)
 * or {@code QUEUED -> CANCELLED} (the HTTP caller gave up first). Exactly one transition wins, so:
 * <ul>
 *   <li>Cancelled: the request is never handed to MySQL. The caller gets a definite refusal (429 SYSTEM_BUSY,
 *       {@code registrar_wait_cancelled}); retrying with the same requestId is safe.</li>
 *   <li>Taken: the request is in, or about to enter, a database transaction. The caller keeps waiting up to
 *       {@code batch-register-taken-wait-ms} for that transaction's outcome. It does NOT assume the 3s Spring
 *       transaction timeout really stops the database work: when that wait also expires the caller gets
 *       503 VALIDATION_UNAVAILABLE (outcome unknown), never a failure claim.</li>
 *   <li>Local buffer full, registrar stopped, or stopped before execution: definite refusal, nothing written.</li>
 * </ul>
 * A 202 receipt is only ever built from a committed batch. The optional {@link Settlement} is told the definite
 * fate of the request whenever it becomes known, even after the caller stopped waiting; an unknown outcome
 * (whole-batch failure) is never reported as either.
 */
@Component
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled","attempts.batch-register"},havingValue="true")
public class AttemptRegistrar {
    static final String UNAVAILABLE="暂时无法确认登记结果，请使用原请求编号查询或重试";
    static final String STOPPED="服务正在停止，本次请求未登记，请使用原请求编号重试";
    /** Definite fate of one request, reported at most once. Implementations must be cheap and must not throw. */
    public interface Settlement {
        /** Committed; {@code newWaiting} tells whether this request inserted a new WAITING row. */
        void committed(boolean newWaiting);
        /** Definitely nothing of this request was committed. */
        void notRegistered();
        Settlement NONE=new Settlement(){public void committed(boolean newWaiting){}public void notRegistered(){}};
    }
    static final int QUEUED=0,TAKEN=1,CANCELLED=2;
    static final class Pending {
        final ReservationAttemptStore.Registration registration;final CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger state=new java.util.concurrent.atomic.AtomicInteger(QUEUED);
        private final Settlement settlement;private final java.util.concurrent.atomic.AtomicBoolean settled=new java.util.concurrent.atomic.AtomicBoolean();
        Pending(ReservationAttemptStore.Registration registration,Settlement settlement){this.registration=registration;this.settlement=settlement==null?Settlement.NONE:settlement;}
        boolean take(){return state.compareAndSet(QUEUED,TAKEN);}
        boolean cancel(){return state.compareAndSet(QUEUED,CANCELLED);}
        void committed(boolean newWaiting){if(settled.compareAndSet(false,true))try{settlement.committed(newWaiting);}catch(RuntimeException ignored){}}
        void notRegistered(){if(settled.compareAndSet(false,true))try{settlement.notRegistered();}catch(RuntimeException ignored){}}
    }
    private final ReservationAttemptStore store;private final MeterRegistry metrics;
    private final int maxBatch;private final long lingerNanos,waitMillis,takenWaitMillis;
    private final BlockingQueue<Pending> pending;
    private volatile boolean running;private volatile Thread worker;
    /** System.nanoTime() when the running batch transaction started, 0 when idle. */
    private volatile long batchStarted;
    /** EWMA of one batch transaction in nanos; used to refuse work that could not commit within the HTTP wait. */
    private final java.util.concurrent.atomic.AtomicLong batchNanos=new java.util.concurrent.atomic.AtomicLong();
    /** Opt-in: refuse up front when the backlog cannot commit within the wait (trades late 503s for early 429s). */
    private final boolean backlogGuard;

    public AttemptRegistrar(ReservationAttemptStore store,MeterRegistry metrics,Environment env){
        this.store=store;this.metrics=metrics;
        maxBatch=env.getProperty("ticketing.reservations-v2.attempts.batch-register-size",Integer.class,16);
        long linger=env.getProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms",Long.class,5L);
        waitMillis=env.getProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms",Long.class,1000L);
        takenWaitMillis=env.getProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms",Long.class,4000L);
        int capacity=env.getProperty("ticketing.reservations-v2.attempts.batch-register-queue",Integer.class,1024);
        backlogGuard=env.getProperty("ticketing.reservations-v2.attempts.batch-register-backlog-guard",Boolean.class,false);
        if(maxBatch<1||maxBatch>256)throw new IllegalArgumentException("batch-register-size must be 1..256");
        if(linger<0||linger>100)throw new IllegalArgumentException("batch-register-linger-ms must be 0..100");
        if(waitMillis<100||waitMillis>2900)throw new IllegalArgumentException("batch-register-wait-ms must be 100..2900 (below the 3s register transaction timeout)");
        if(takenWaitMillis<100||takenWaitMillis>60000)throw new IllegalArgumentException("batch-register-taken-wait-ms must be 100..60000");
        if(capacity<1||capacity>65536)throw new IllegalArgumentException("batch-register-queue must be 1..65536");
        lingerNanos=TimeUnit.MILLISECONDS.toNanos(linger);pending=new ArrayBlockingQueue<>(capacity);
        Gauge.builder("attempt.register.batch.pending",pending,BlockingQueue::size).register(metrics);
        Gauge.builder("attempt.register.batch.inflight.ms",this,r->{long s=r.batchStarted;return s==0?0:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-s);}).register(metrics);
    }
    /** Longest time one HTTP caller can wait: the queue wait plus the taken wait. */
    long maxCallerWaitMillis(){return waitMillis+takenWaitMillis;}
    @PostConstruct public void start(){
        running=true;Thread t=new Thread(this::loop,"attempt-registrar");t.setDaemon(true);worker=t;t.start();
    }
    /**
     * Stops taking new work, lets a running batch finish (bounded by the taken wait), then refuses everything that
     * was never taken. A batch that is still running after that keeps its unknown outcome for its callers.
     */
    @PreDestroy public void stop(){
        running=false;Thread t=worker;
        if(t!=null&&t!=Thread.currentThread()){
            try{t.join(takenWaitMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(t.isAlive())t.interrupt();
        }
        List<Pending> left=new ArrayList<>();pending.drainTo(left);
        for(Pending p:left)if(p.cancel())refuseStopped(p);
    }

    /** Same contract as {@link ReservationAttemptStore#register}. */
    public Map<String,Object> register(ReserveRequest r,String ip,String fingerprint,long version){return register(r,ip,fingerprint,version,Settlement.NONE);}
    public Map<String,Object> register(ReserveRequest r,String ip,String fingerprint,long version,Settlement settlement){
        Pending p=new Pending(new ReservationAttemptStore.Registration(r,ip,fingerprint,version),settlement);
        if(!running){p.notRegistered();throw stopped();}
        // Backlog guard: if the queued batches alone would take longer than the HTTP wait, refuse now (429, nothing
        // written) instead of accepting work whose receipt would time out into a 503 after it commits anyway.
        if(backlogGuard&&pending.size()>=backlogLimit()||!pending.offer(p)){p.notRegistered();throw store.registrarFull();}
        try{return outcome(p,waitMillis);}
        catch(TimeoutException slow){
            metrics.counter("attempt.register.batch.wait.timeout").increment();
            if(p.cancel()){
                pending.remove(p);p.notRegistered();
                metrics.counter("attempt.register.batch.cancelled").increment();
                throw store.registrarCancelled();
            }
            // TAKEN: the transaction may commit. Wait for its definite outcome, bounded; never guess.
            metrics.counter("attempt.register.batch.taken.wait").increment();
            try{return outcome(p,takenWaitMillis);}
            catch(TimeoutException stillUnknown){metrics.counter("attempt.register.batch.taken.timeout").increment();throw unknown();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unknown();}
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            if(p.cancel()){pending.remove(p);p.notRegistered();throw stopped();}
            throw unknown();
        }
    }
    private static Map<String,Object> outcome(Pending p,long millis)throws TimeoutException,InterruptedException{
        try{return p.future.get(millis,TimeUnit.MILLISECONDS);}
        catch(ExecutionException failed){
            Throwable cause=failed.getCause();
            if(cause instanceof RuntimeException)throw (RuntimeException)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw new IllegalStateException(cause);
        }
    }
    private static AttemptAdmissionException unknown(){return new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE",UNAVAILABLE);}
    private static AttemptAdmissionException stopped(){return new AttemptAdmissionException(503,"SYSTEM_BUSY",STOPPED);}
    private void refuseStopped(Pending p){p.notRegistered();p.future.completeExceptionally(stopped());metrics.counter("attempt.register.batch.stopped").increment();}

    int backlogLimit(){
        long per=batchNanos.get();if(per<=0)return Integer.MAX_VALUE;
        long batches=TimeUnit.MILLISECONDS.toNanos(waitMillis)*8/10/per; // keep 20% of the wait for the caller's own batch
        return (int)Math.max(maxBatch,Math.min(Integer.MAX_VALUE,batches*maxBatch));
    }
    private void loop(){
        while(running){
            List<Pending> batch=new ArrayList<>(maxBatch);
            try{
                Pending first=pending.poll(100,TimeUnit.MILLISECONDS);
                if(first==null||!first.take())continue; // cancelled while queued: never reaches MySQL
                batch.add(first);
                long deadline=System.nanoTime()+lingerNanos;
                while(batch.size()<maxBatch){
                    Pending next=pending.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                    if(next==null)break;
                    if(next.take())batch.add(next);
                }
            }catch(InterruptedException interrupted){
                // Taken but never executed: definitely not written.
                for(Pending p:batch)refuseStopped(p);
                if(!running)return;
                continue;
            }
            execute(batch);
        }
    }
    /** Only TAKEN items are ever passed here. */
    void execute(List<Pending> batch){
        List<ReservationAttemptStore.Registration> items=new ArrayList<>(batch.size());
        for(Pending p:batch)items.add(p.registration);
        long started=System.nanoTime();batchStarted=started;
        try{store.registerBatch(items);}
        catch(RuntimeException|Error whole){
            // Whole-batch failure (deadlock, timeout, commit failure): every caller gets the original exception,
            // which the entry maps exactly as a failed single registration (including an unknown commit outcome).
            // No settlement: the outcome is unknown.
            for(Pending p:batch)p.future.completeExceptionally(whole);
            return;
        }finally{batchStarted=0;long took=System.nanoTime()-started;batchNanos.updateAndGet(old->old==0?took:(old*7+took)/8);}
        // Committed. A per-item failure was rolled back to that item's savepoint: definitely not written.
        for(Pending p:batch){
            if(p.registration.failure()!=null){p.notRegistered();p.future.completeExceptionally(p.registration.failure());}
            else{p.committed(Boolean.TRUE.equals(p.registration.result().get("newlyCreated")));p.future.complete(p.registration.result());}
        }
    }
}
