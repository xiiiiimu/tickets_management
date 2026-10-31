package com.ticketing.ticket;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import java.util.concurrent.TimeUnit;

/** Total is inclusive; begin/body/completion are disjoint. Never sum total with its parts. */
final class AttemptTransactionTiming {
    static <T>T execute(TransactionTemplate tx,MeterRegistry metrics,String operation,TransactionCallback<T> callback){
        return execute(tx,metrics,operation,callback,System::nanoTime);
    }
    static <T>T execute(TransactionTemplate tx,MeterRegistry metrics,String operation,TransactionCallback<T> callback,java.util.function.LongSupplier clock){
        long start=clock.getAsLong();long[] bounds={0,0};boolean[] rollback={false};String outcome="failure";
        try{T result=tx.execute(status->{bounds[0]=clock.getAsLong();try{return callback.doInTransaction(status);}finally{rollback[0]=status.isRollbackOnly();bounds[1]=clock.getAsLong();}});outcome=rollback[0]?"failure":"success";return result;}
        finally{
            long end=clock.getAsLong(),begin=bounds[0]==0?end:bounds[0],bodyEnd=bounds[1]==0?begin:bounds[1];
            record(metrics,operation,outcome,"total",end-start);record(metrics,operation,outcome,"begin",begin-start);
            record(metrics,operation,outcome,"body",bodyEnd-begin);record(metrics,operation,outcome,"completion",end-bodyEnd);
        }
    }
    private static void record(MeterRegistry registry,String operation,String outcome,String phase,long nanos){io.micrometer.core.instrument.Timer.builder("attempt.transaction.duration").tags("operation",operation,"outcome",outcome,"phase",phase)
        .serviceLevelObjectives(java.time.Duration.ofMillis(1),java.time.Duration.ofMillis(5),java.time.Duration.ofMillis(10),java.time.Duration.ofMillis(25),java.time.Duration.ofMillis(50),java.time.Duration.ofMillis(100),java.time.Duration.ofMillis(250),java.time.Duration.ofMillis(500),java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(2),java.time.Duration.ofSeconds(5))
        .register(registry).record(Math.max(0,nanos),TimeUnit.NANOSECONDS);}
}
