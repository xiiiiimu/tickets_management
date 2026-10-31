package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Startup gate only. One application-ready probe; retries use the existing isolated control. */
@Component
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled"},havingValue="true")
public class AttemptWorkerReadiness {
    private final Db db;private final StringRedisTemplate redis;private final TicketSaleArmory armory;
    private final ObjectProvider<ReservationAttemptStore> stores;private final MeterRegistry metrics;private final TransactionTemplate warm;
    private final ApplicationContext context;
    private final AtomicBoolean checking=new AtomicBoolean();private volatile boolean applicationReady,ready;private volatile long nextProbe;
    public AttemptWorkerReadiness(Db db,StringRedisTemplate redis,TicketSaleArmory armory,ObjectProvider<ReservationAttemptStore> stores,
                                  MeterRegistry metrics,PlatformTransactionManager manager,ApplicationContext context){
        this.db=db;this.redis=redis;this.armory=armory;this.stores=stores;this.metrics=metrics;this.context=context;
        warm=new TransactionTemplate(manager);warm.setTimeout(5);
        io.micrometer.core.instrument.Gauge.builder("attempt.worker.ready",this,x->x.isReady()?1:0).register(metrics);
    }
    @EventListener public void applicationReady(ApplicationReadyEvent event){if(event.getApplicationContext()==context){applicationReady=true;ensureReady();}}
    public boolean isReady(){return ready&&applicationReady;}
    public boolean ensureReady(){
        if(isReady())return true;
        if(!applicationReady||System.nanoTime()<nextProbe||!checking.compareAndSet(false,true))return false;
        String stage="database";
        try{
            if(!Integer.valueOf(1).equals(db.jdbc.queryForObject("SELECT 1",Integer.class)))throw new IllegalStateException("Database probe failed");
            stage="redis";
            String pong=redis.execute((RedisCallback<String>)connection->connection.ping());
            if(!"PONG".equalsIgnoreCase(pong))throw new IllegalStateException("Redis probe failed");
            stage="protocol";stores.getObject().initialize();
            stage="armory";
            db.jdbc.queryForList("SELECT session_id,state,version,active_version FROM tb_ticket_sale_assembly WHERE 1=0");
            // Warm only currently valid READY recovery contexts. Retired/changed business
            // contexts must still reach the original fenced rejection/compensation path.
            List<Map<String,Object>> pending=db.jdbc.queryForList("SELECT DISTINCT j.session_id,j.sku_id,a.active_version FROM tb_reservation_job j JOIN tb_ticket_sku s ON s.id=j.sku_id JOIN tb_ticket_sale_assembly a ON a.session_id=j.session_id WHERE j.attempt_protocol IN (3,4) AND j.state IN ('WAITING','PROCESSING') AND a.state='READY' AND a.version=a.active_version AND s.status='ON_SALE' AND s.sale_start_time<=CURRENT_TIMESTAMP(3) AND s.sale_end_time>CURRENT_TIMESTAMP(3) LIMIT 513");
            if(pending.size()>512)throw new IllegalStateException("Pending contexts exceed the existing queue bound");
            for(Map<String,Object> job:pending)warm.executeWithoutResult(status->armory.runtimeSku(job.get("session_id").toString(),job.get("sku_id").toString(),((Number)job.get("active_version")).longValue()));
            ready=true;metrics.counter("attempt.worker.readiness","result","ready","stage","complete").increment();return true;
        }catch(RuntimeException failure){
            metrics.counter("attempt.worker.readiness","result","unavailable","stage",stage).increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=attempt_worker_not_ready stage={} failure_type={}",stage,failure.getClass().getSimpleName());return false;
        }finally{nextProbe=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);checking.set(false);}
    }
}
