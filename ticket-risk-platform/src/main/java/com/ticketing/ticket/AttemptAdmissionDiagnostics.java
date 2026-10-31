package com.ticketing.ticket;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/** No diagnostic SQL and no request/user identifiers. Logs at most once per reason per 5s. */
final class AttemptAdmissionDiagnostics {
    private final MeterRegistry metrics;private final DataSource source;
    private final AtomicLong dbLog=new AtomicLong(),capacityLog=new AtomicLong();
    private volatile Snapshot snapshot=new Snapshot(-1,-1,-1,-1,0);
    private volatile IntSupplier localWorkers=()->-1;
    static final class Snapshot {
        final int waiting,budget,processing,capacity;final long nano;
        Snapshot(int w,int b,int p,int c,long n){waiting=w;budget=b;processing=p;capacity=c;nano=n;}
    }
    AttemptAdmissionDiagnostics(MeterRegistry metrics,DataSource source){this.metrics=metrics;this.source=source;}
    void workers(IntSupplier supplier){localWorkers=supplier;}
    void observe(int waiting,int budget,int processing,int capacity){snapshot=new Snapshot(waiting,budget,processing,capacity,System.nanoTime());}
    AttemptAdmissionException capacity(String detail){return refusal("CAPACITY_REJECT",detail);}
    AttemptAdmissionException gateRejected(){AttemptAdmissionException result=refusal("GATE_REJECT","redis_gate");record(result);return result;}
    AttemptAdmissionException dbBusy(){AttemptAdmissionException result=refusal("DB_BUSY","pool_busy");record(result);return result;}
    private Refusal refusal(String reason,String detail){
        int active=-1,waiting=-1;
        if(source instanceof HikariDataSource){HikariPoolMXBean pool=((HikariDataSource)source).getHikariPoolMXBean();if(pool!=null){active=pool.getActiveConnections();waiting=pool.getThreadsAwaitingConnection();}}
        return new Refusal(reason,detail,snapshot,localWorkers.getAsInt(),active,waiting,System.nanoTime());
    }
    private static final class Refusal extends AttemptAdmissionException {
        final String reason,detail;final Snapshot snapshot;final int workers,active,waiting;final long nano;
        Refusal(String reason,String detail,Snapshot snapshot,int workers,int active,int waiting,long nano){super(429,"SYSTEM_BUSY","系统繁忙，请使用原请求编号稍后重试");this.reason=reason;this.detail=detail;this.snapshot=snapshot;this.workers=workers;this.active=active;this.waiting=waiting;this.nano=nano;}
    }
    void record(AttemptAdmissionException exception){
        if(!(exception instanceof Refusal))return;
        Refusal refusal=(Refusal)exception;
        metrics.counter("attempt.admission.rejected.internal","reason",refusal.reason,"detail",refusal.detail).increment();
        org.slf4j.Logger logger=org.slf4j.LoggerFactory.getLogger(AttemptAdmissionDiagnostics.class);if(!logger.isWarnEnabled())return;
        long now=System.nanoTime();AtomicLong last="DB_BUSY".equals(refusal.reason)?dbLog:capacityLog;long prior=last.get();
        if(prior!=0&&now-prior<java.util.concurrent.TimeUnit.SECONDS.toNanos(5)||!last.compareAndSet(prior,now))return;
        Snapshot s=refusal.snapshot;
        logger.warn(
            "event=attempt_admission_rejected reason={} detail={} queue_waiting={} queue_capacity={} capacity_budget={} processing_global={} workers_local={} hikari_active={} hikari_waiting={} snapshot_age_ms={} snapshot_source=cached",
            refusal.reason,refusal.detail,s.waiting,s.capacity,s.budget,s.processing,refusal.workers,refusal.active,refusal.waiting,s.nano==0?-1:(refusal.nano-s.nano)/1000000);
        metrics.counter("attempt.admission.diagnostic.logs","reason",refusal.reason).increment();
    }
}
