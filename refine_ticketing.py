from pathlib import Path
root=Path(__file__).parent
base=root/'ticket-risk-platform/src/main/java/com/ticketing'
def change(path,old,new):
 p=base/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old);p.write_text(s.replace(old,new),encoding='utf-8')
change('TicketPlatformApplication.java','@EnableScheduling','@org.mybatis.spring.annotation.MapperScan({"com.ticketing.user.mapper","com.ticketing.audit.mapper","com.ticketing.workflow.mapper"})\n@EnableScheduling')
change('inventory/InventoryService.java','public void configure(Map<String,Object> sku){','@org.springframework.transaction.annotation.Transactional\n public void configure(Map<String,Object> sku){\n  sku=db.one("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",sku.get("id"));')
change('inventory/InventoryService.java','redis.opsForValue().setIfAbsent(p+"stock",sku.get("remaining_stock").toString());','redis.opsForValue().set(p+"stock",sku.get("remaining_stock").toString());')
change('ticket/ReservationIntentService.java','db.jdbc.update("INSERT INTO tb_reserve_request','db.one("SELECT id FROM tb_ticket_sku WHERE id=? FOR UPDATE",r.getTicketSkuId());\n  db.jdbc.update("INSERT INTO tb_reserve_request')
change('risk/RiskDecisionService.java','String id=Db.id();db.jdbc.update("INSERT INTO tb_risk_decision(id,order_id,phase,decision,score,features,matched_rules,agent_analysis) VALUES(?,?,?,?,?,?,?,?)",id,order.get("id"),phase,decision.name(),adjusted,db.json(f),db.json(score.getMatchedRules()),db.json(analysis));','String id=Db.id();db.jdbc.update("INSERT INTO tb_risk_decision(id,order_id,phase,decision,score,features,matched_rules,agent_analysis,context_key) VALUES(?,?,?,?,?,?,?,?,?)",id,order.get("id"),phase,decision.name(),adjusted,db.json(f),db.json(score.getMatchedRules()),db.json(analysis),db.json(Arrays.asList(device,ip)));')
p=base/'payment/WalletService.java';s=p.read_text(encoding='utf-8');start=s.index('  Map<String,Object> approval=');end=s.index('\n',start)
s=s[:start]+'''  Map<String,Object> approval=db.one("SELECT t.id FROM tb_manual_review_ticket t JOIN tb_risk_decision d ON d.id=t.risk_decision_id WHERE t.order_id=? AND t.phase='PAYMENT' AND t.status='APPROVE' AND d.context_key=? ORDER BY t.created_at DESC LIMIT 1",orderId,db.json(Arrays.asList(request.getDeviceId(),ip)));'''+s[end:];p.write_text(s,encoding='utf-8')
for name in ['schema.sql','db/migration/V100__ticketing_schema.sql']:
 p=root/'ticket-risk-platform/src/main/resources'/name;s=p.read_text(encoding='utf-8').replace('agent_analysis TEXT, created_at','agent_analysis TEXT, context_key VARCHAR(512) NOT NULL, created_at');p.write_text(s,encoding='utf-8')
change('outbox/OutboxPublisher.java','transport.syncSend(row.get("topic").toString(),MessageBuilder.withPayload(row.get("payload").toString()).setHeader("KEYS",row.get("message_key")).build(),3000);','org.apache.rocketmq.client.producer.SendResult sent=transport.syncSend(row.get("topic").toString(),MessageBuilder.withPayload(row.get("payload").toString()).setHeader("KEYS",row.get("message_key")).build(),3000);\n    if(sent.getSendStatus()!=org.apache.rocketmq.client.producer.SendStatus.SEND_OK)throw new IllegalStateException("Broker did not durably acknowledge");')
change('outbox/OutboxPublisher.java','public void compensate(){','public void compensate(){db.jdbc.update("UPDATE tb_outbox_message o SET o.status=\'RETRY\',o.next_retry_time=CURRENT_TIMESTAMP WHERE o.status=\'SENT\' AND o.updated_at<? AND NOT EXISTS (SELECT 1 FROM tb_message_consume_record c WHERE c.consumer_group=\'ticket-events\' AND c.message_key=o.message_key AND c.status=\'DONE\')",new Timestamp(System.currentTimeMillis()-300000));')
change('mq/RocketTicketListener.java','private final TicketEventConsumer consumer;','private final com.ticketing.infrastructure.TaskPools pools;\n private final TicketEventConsumer consumer;')
change('mq/RocketTicketListener.java','TicketEventConsumer consumer,ObjectMapper json){this.consumer=consumer;this.json=json;}','TicketEventConsumer consumer,ObjectMapper json,com.ticketing.infrastructure.TaskPools pools){this.consumer=consumer;this.json=json;this.pools=pools;}')
change('mq/RocketTicketListener.java','consumer.consume(m.get("messageKey").toString(),m.get("orderId").toString(),m.get("type").toString());','java.util.concurrent.CompletableFuture<Void> done=new java.util.concurrent.CompletableFuture<>();pools.execute("risk",()->{try{consumer.consume(m.get("messageKey").toString(),m.get("orderId").toString(),m.get("type").toString());done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});done.get(30,java.util.concurrent.TimeUnit.SECONDS);')
# The original generic cache now uses the configured executor and token-checked lock release.
p=base/'common/util/CacheClient.java';s=p.read_text(encoding='utf-8');s=s.replace('private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);','@org.springframework.beans.factory.annotation.Autowired(required=false)\n    private com.ticketing.infrastructure.TaskPools taskPools;')
s=s.replace('CACHE_REBUILD_EXECUTOR.submit(()->{','Runnable rebuild=()->{').replace('            });\n\n        }','            };\n            try { if(taskPools!=null)taskPools.execute("cache",rebuild);else rebuild.run(); } catch(RuntimeException e){unlock(lockKey);throw e;}\n\n        }')
# Generic cache rebuild obtains its owner token before leaving the caller thread.
s=s.replace('    private boolean tryLock(String key){','    private final java.util.concurrent.ConcurrentHashMap<String,String> lockTokens=new java.util.concurrent.ConcurrentHashMap<>();\n    private boolean tryLock(String key){')
s=s.replace('Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10,TimeUnit.SECONDS);','String token=java.util.UUID.randomUUID().toString();\n        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10,TimeUnit.SECONDS);\n        if(Boolean.TRUE.equals(flag))lockTokens.put(key,token);')
# A map keyed only by cache key is unsafe on lease expiry, so use an explicit token capture for asynchronous work.
s=s.replace('        if(isLock){\n            Runnable rebuild','        if(isLock){\n            final String rebuildToken=lockTokens.remove(lockKey);\n            Runnable rebuild').replace('                    unlock(lockKey);','                    unlock(lockKey,rebuildToken);')
s=s.replace('        stringRedisTemplate.delete(key);','        unlock(key,lockTokens.remove(key));').replace('\n    }\n\n}', '''
    }
    private void unlock(String key,String token){
        if(token==null)return;
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script=new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setScriptText("if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0");
        script.setResultType(Long.class);
        stringRedisTemplate.execute(script,java.util.Collections.singletonList(key),token);
    }
}
''')
p.write_text(s,encoding='utf-8')
