package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Acquires the global protocol-3 coordinator row ({@code tb_reservation_dispatch id=1}) and measures it.
 *
 * <p>Metric {@code attempt.coordinator.lock{operation,phase,outcome}} (timer), measured on the JVM monotonic clock:
 * <ul>
 *   <li>{@code phase=wait}: from just before sending {@code SELECT ... FOR UPDATE} until it returns. This is the
 *       time spent queueing for the row lock, plus one statement round trip (sub-millisecond when uncontended).
 *       {@code outcome=acquired}, or {@code not_acquired} when the statement failed (query/transaction timeout,
 *       deadlock, connection loss).</li>
 *   <li>{@code phase=hold}: from the moment that statement returned (lock granted) until the transaction completion
 *       callback, i.e. after COMMIT/ROLLBACK returned to the driver. It covers every statement executed under the
 *       lock plus the commit (InnoDB keeps the row lock until the commit, including its redo flush). It is an upper
 *       bound of the server-side hold by one commit round trip. {@code outcome=commit|rollback|unknown}.</li>
 * </ul>
 * The transaction timer {@code attempt.transaction.duration phase=body} still includes the lock wait and must not be
 * read as the hold time. Purely observational: no SQL, ordering or transaction boundary is changed.
 */
final class AttemptCoordinatorLock {
    static final String SQL="SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE";
    static final String METRIC="attempt.coordinator.lock";
    private AttemptCoordinatorLock(){}

    static Map<String,Object> acquire(Db db,MeterRegistry metrics,String operation){return acquire(db,metrics,operation,System::nanoTime);}

    static Map<String,Object> acquire(Db db,MeterRegistry metrics,String operation,LongSupplier clock){
        long sent=clock.getAsLong();Map<String,Object> settings;
        try{settings=db.one(SQL);}
        catch(RuntimeException failed){record(metrics,operation,"wait","not_acquired",clock.getAsLong()-sent);throw failed;}
        long acquired=clock.getAsLong();
        record(metrics,operation,"wait","acquired",acquired-sent);
        if(TransactionSynchronizationManager.isSynchronizationActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){
                    String outcome=status==STATUS_COMMITTED?"commit":status==STATUS_ROLLED_BACK?"rollback":"unknown";
                    record(metrics,operation,"hold",outcome,clock.getAsLong()-acquired);
                }
            });
        }else metrics.counter(METRIC+".hold.untracked","operation",operation).increment();
        return settings;
    }

    private static void record(MeterRegistry registry,String operation,String phase,String outcome,long nanos){
        Timer.builder(METRIC).tags("operation",operation,"phase",phase,"outcome",outcome)
            .serviceLevelObjectives(Duration.ofMillis(1),Duration.ofMillis(5),Duration.ofMillis(10),Duration.ofMillis(25),Duration.ofMillis(50),
                Duration.ofMillis(100),Duration.ofMillis(250),Duration.ofMillis(500),Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5))
            .register(registry).record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
    }
}
