package com.ticketing.ticket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional control thread only. Does not execute risk evaluation or inventory work. */
@Component
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationDispatchScheduler {
    private final boolean isolated,wakeup;
    private final long pollMillis;
    private final MeterRegistry metrics;
    private final AtomicBoolean wakePending=new AtomicBoolean();
    private volatile ScheduledThreadPoolExecutor executor;
    private volatile Runnable dispatch;
    private volatile boolean closed;

    public ReservationDispatchScheduler(Environment env,MeterRegistry metrics){
        this.metrics=metrics;
        isolated=env.getProperty("ticketing.reservations-v2.dispatch.isolated",Boolean.class,false);
        wakeup=env.getProperty("ticketing.reservations-v2.dispatch.wakeup",Boolean.class,false);
        pollMillis=env.getProperty("ticketing.reservations-v2.poll-ms",Long.class,25L);
        if(pollMillis<1)throw new IllegalArgumentException("Dispatch polling must remain bounded");
        if(wakeup&&!isolated)throw new IllegalArgumentException("Dispatch wakeup requires isolated dispatch");
        Gauge.builder("reservation.dispatch.control.queue",this,x->{ScheduledThreadPoolExecutor e=x.executor;return e==null?0:e.getQueue().size();}).register(metrics);
    }
    public boolean isIsolated(){return isolated;}
    public synchronized void start(Runnable task){
        if(!isolated||closed)return;
        if(executor!=null)throw new IllegalStateException("Dispatch control already started");
        dispatch=task;
        ScheduledThreadPoolExecutor e=new ScheduledThreadPoolExecutor(1,r->new Thread(r,"sale-dispatch-control"),new ThreadPoolExecutor.AbortPolicy());
        e.setRemoveOnCancelPolicy(true);executor=e;
        e.scheduleWithFixedDelay(this::runSafely,0,pollMillis,TimeUnit.MILLISECONDS);
    }
    private void runSafely(){
        if(closed)return;
        try{dispatch.run();}
        catch(RuntimeException failure){
            metrics.counter("reservation.dispatch.control.failed").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dispatch control deferred; durable leases remain recoverable",failure);
        }
    }
    public void afterCommitWake(){
        if(!wakeup)return;
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Dispatch notification requires a durable task transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){signal();}
        });
    }
    private void signal(){
        ScheduledThreadPoolExecutor e=executor;
        if(closed||e==null)return;
        if(!wakePending.compareAndSet(false,true)){metrics.counter("reservation.dispatch.wakeup","result","coalesced").increment();return;}
        try{
            e.execute(()->{try{runSafely();}finally{wakePending.set(false);}});
            metrics.counter("reservation.dispatch.wakeup","result","submitted").increment();
        }catch(RejectedExecutionException stopped){
            wakePending.set(false);metrics.counter("reservation.dispatch.wakeup","result","rejected").increment();
            // A committed QUEUED row must not be reclassified as failed by a callback exception.
        }
    }
    @PreDestroy public synchronized void close(){
        closed=true;ScheduledThreadPoolExecutor e=executor;
        if(e!=null)e.shutdownNow();
    }
}
