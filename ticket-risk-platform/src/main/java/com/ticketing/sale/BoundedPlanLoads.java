package com.ticketing.sale;

import com.ticketing.ticket.AttemptAdmissionException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Caller-run leaders; no executor, unbounded task queue, or lock held during I/O. */
final class BoundedPlanLoads<T> {
    private final ConcurrentHashMap<String,CompletableFuture<T>> flights=new ConcurrentHashMap<>();
    private final Semaphore leaders,waiters;
    private final MeterRegistry metrics;
    BoundedPlanLoads(MeterRegistry metrics,int keys,int waiting){this.metrics=metrics;leaders=new Semaphore(keys);waiters=new Semaphore(waiting);}
    T get(String key,Supplier<T> loader,long waitMillis){
        if(waitMillis<1||waitMillis>30000)throw new IllegalArgumentException("plan-load-wait-ms must be 1..30000");
        CompletableFuture<T> mine=new CompletableFuture<>(),current=flights.get(key);
        if(current==null){
            if(!leaders.tryAcquire())throw unavailable("load_capacity");
            current=flights.putIfAbsent(key,mine);
            if(current!=null)leaders.release();
            else{
                long begin=System.nanoTime();String outcome="failure";
                try{T value=loader.get();mine.complete(value);outcome="success";return value;}
                catch(RuntimeException|Error failure){mine.completeExceptionally(failure);throw failure;}
                finally{flights.remove(key,mine);leaders.release();metrics.timer("sale.plan.load","outcome",outcome).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
            }
        }
        if(!waiters.tryAcquire())throw unavailable("wait_capacity");
        metrics.counter("sale.plan.merged").increment();long begin=System.nanoTime();String outcome="failure";
        try{T value=current.get(waitMillis,TimeUnit.MILLISECONDS);outcome="success";return value;}
        catch(TimeoutException timeout){outcome="timeout";throw unavailable("wait_timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();outcome="interrupted";throw unavailable("interrupted");}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof RuntimeException)throw (RuntimeException)cause;if(cause instanceof Error)throw (Error)cause;throw new IllegalStateException(cause);}
        finally{waiters.release();metrics.timer("sale.plan.wait","outcome",outcome).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
    private AttemptAdmissionException unavailable(String reason){metrics.counter("sale.plan.rejected","reason",reason).increment();return new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","销售计划校验暂不可用，请使用原请求编号稍后重试");}
}
