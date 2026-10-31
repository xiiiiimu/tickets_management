package com.ticketing.mq;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
@Component @ConditionalOnProperty(name="ticketing.mq.enabled",havingValue="true",matchIfMissing=true)
@RocketMQMessageListener(topic="ticket-events",consumerGroup="ticket-events",consumeThreadMax=16)
public class RocketTicketListener implements RocketMQListener<String> {
 private final com.ticketing.infrastructure.TaskPools pools;
 private final TicketEventConsumer consumer;private final ObjectMapper json;
 public RocketTicketListener(TicketEventConsumer consumer,ObjectMapper json,com.ticketing.infrastructure.TaskPools pools){this.consumer=consumer;this.json=json;this.pools=pools;}
 public void onMessage(String payload){try{Map<?,?> m=json.readValue(payload,Map.class);String type=m.get("type").toString();
  String key=m.get("messageKey").toString();String orderId=m.get("orderId").toString();
  // AUTO_PAYMENT: run on RocketMQ consumer thread (consumeThreadMax) — do not nest TaskPools+Future.get.
  if("AUTO_PAYMENT".equals(type)){consumer.consume(key,orderId,type);return;}
  // RISK_CHECK is post-trade only and must not pin MQ threads behind risk-pool Future.get (starves payment/issue).
  if("RISK_CHECK".equals(type)){
   pools.execute("risk",()->{try{consumer.consume(key,orderId,type);}catch(Throwable error){
    org.slf4j.LoggerFactory.getLogger(RocketTicketListener.class).error("RISK_CHECK async failed key="+key,error);}});
   return;
  }
  // ISSUE/RELEASE: keep synchronous handoff so broker redelivery still covers fulfillment failures.
  java.util.concurrent.CompletableFuture<Void> done=new java.util.concurrent.CompletableFuture<>();
  pools.execute("outbox",()->{try{consumer.consume(key,orderId,type);done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});
  done.get(30,java.util.concurrent.TimeUnit.SECONDS);
 }catch(Exception e){throw new IllegalStateException("Ticket event must retry",e);}}
}
