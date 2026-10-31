package com.ticketing.ticket;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Observation only: wall time of one step of an attempt's critical path, timer {@code attempt.path{phase,outcome}}.
 * Never changes control flow; exceptions propagate unchanged (outcome=error).
 */
final class AttemptPath {
    private AttemptPath(){}
    static <T> T time(MeterRegistry metrics,String phase,Supplier<T> step){
        long begin=System.nanoTime();String outcome="error";
        try{T value=step.get();outcome="ok";return value;}
        finally{record(metrics,phase,outcome,System.nanoTime()-begin);}
    }
    static void run(MeterRegistry metrics,String phase,Runnable step){time(metrics,phase,()->{step.run();return null;});}
    static void record(MeterRegistry metrics,String phase,String outcome,long nanos){
        metrics.timer("attempt.path","phase",phase,"outcome",outcome).record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
    }
}
