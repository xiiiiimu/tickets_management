package com.ticketing.infrastructure;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.*;
@Component
public class PlatformMetrics {
 public PlatformMetrics(Db db,MeterRegistry registry){
  for(String name:new String[]{
          "ticket.reserve.success","ticket.reserve.failed","order.created","order.cancelled",
          "risk.pass","risk.review","risk.block","risk.analyze.completed",
          "risk.fast.allow","risk.fast.block","risk.fast.rate_limited","risk.fast.blacklist","risk.fast.profile_block",
          "risk.agent.call","risk.agent.unavailable","risk.agent.error",
          "payment.success","payment.failed","payment.auto.attempt","payment.auto.success","payment.auto.failure",
          "mq.consume.duplicate","mq.outbox.publish.success","mq.outbox.publish.retry","mq.outbox.publish.failed"
  })registry.counter(name);
  for(String name:new String[]{"mq.outbox.pending","mq.outbox.sent","mq.outbox.failed","mq.outbox.retry",
          "ticket.stock.remaining","risk.profile.block_count"})
      Gauge.builder(name,this,s->s.snapshot.getOrDefault(name,Double.NaN)).register(registry);
  Gauge.builder("platform.snapshot.age",this,s->s.lastSuccess==0?Double.NaN:(System.currentTimeMillis()-s.lastSuccess)/1000d).register(registry);
  this.db=db;this.registry=registry;
 }
 private final Db db;
 private final MeterRegistry registry;
 private volatile java.util.Map<String,Double> snapshot=java.util.Collections.emptyMap();
 private volatile long lastSuccess;
 @org.springframework.beans.factory.annotation.Value("${ticketing.inventory.bucket-count:1}")
 private int bucketCount=1;
 @org.springframework.beans.factory.annotation.Value("${ticketing.metrics.snapshot.enabled:true}")
 private boolean snapshotEnabled=true;
 /** Sampling is independent of Prometheus scrapes; a failed sample keeps the last known values. */
 @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${ticketing.metrics.snapshot.interval-ms:15000}")
 public void refreshSnapshot(){
  if(!snapshotEnabled)return;
  long begin=System.nanoTime();
  try{
   java.util.Map<String,Double> next=new java.util.HashMap<>();
   double pending=0,sent=0,failed=0,retry=0;
   for(java.util.Map<String,Object> row:db.jdbc.queryForList("SELECT status,COUNT(*) total FROM tb_outbox_message GROUP BY status")){
    double n=((Number)row.get("total")).doubleValue();String status=String.valueOf(row.get("status"));
    if(java.util.Arrays.asList("PENDING","RETRY","SENDING").contains(status))pending+=n;
    if("SENT".equals(status))sent+=n;if("FAILED".equals(status))failed+=n;if("RETRY".equals(status))retry+=n;
   }
   next.put("mq.outbox.pending",pending);next.put("mq.outbox.sent",sent);
   next.put("mq.outbox.failed",failed);next.put("mq.outbox.retry",retry);
   String stocks=bucketCount==16
     ?"SELECT COALESCE(SUM(COALESCE(b.remaining,s.remaining_stock)),0) FROM tb_ticket_sku s LEFT JOIN "
       +"(SELECT sku_id,SUM(remaining_stock) remaining FROM tb_ticket_stock_bucket GROUP BY sku_id) b ON b.sku_id=s.id"
     :"SELECT COALESCE(SUM(remaining_stock),0) FROM tb_ticket_sku";
   next.put("ticket.stock.remaining",db.jdbc.queryForObject(stocks,Double.class));
   next.put("risk.profile.block_count",db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile WHERE decision='BLOCK' AND expires_at>CURRENT_TIMESTAMP",Double.class));
   snapshot=java.util.Collections.unmodifiableMap(next);lastSuccess=System.currentTimeMillis();
  }catch(RuntimeException error){
   registry.counter("platform.snapshot.failure").increment();
   org.slf4j.LoggerFactory.getLogger(PlatformMetrics.class).warn("Metrics snapshot failed; retaining previous sample",error);
  }finally{registry.timer("platform.snapshot.duration").record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);}
 }
}
