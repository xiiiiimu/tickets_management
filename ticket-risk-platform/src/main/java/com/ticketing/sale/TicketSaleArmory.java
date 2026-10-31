package com.ticketing.sale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.*;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.risk.*;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import com.ticketing.ticket.TicketSku;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional session-level assembly; inventory and fulfillment remain owned by existing services. */
@Service
@ConditionalOnProperty(name="ticketing.sale.armory.enabled",havingValue="true")
public class TicketSaleArmory {
    private final Db db;
    private final StringRedisTemplate redis;
    private final TicketInventoryRepository skus;
    private final StockBucketService buckets;
    private final InventoryService inventory;
    private final RiskDecisionService risk;
    private final RiskRuleEvaluatorRegistry evaluators;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final String share;
    private final SaleManifestCache parsedManifests = new SaleManifestCache(128);
    @org.springframework.beans.factory.annotation.Value("${ticketing.reservations-v2.enabled:false}")
    private boolean reservationsV2;
    private final DefaultRedisScript<Long> claimScript=script("sale_assembly_claim");
    private final DefaultRedisScript<Long> publishScript=script("sale_manifest_publish");
    private static DefaultRedisScript<Long> script(String name){
        DefaultRedisScript<Long> script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("lua/"+name+".lua"));script.setResultType(Long.class);return script;
    }

    public TicketSaleArmory(Db db,StringRedisTemplate redis,TicketInventoryRepository skus,
            StockBucketService buckets,InventoryService inventory,RiskDecisionService risk,
            RiskRuleEvaluatorRegistry evaluators,ObjectMapper json,MeterRegistry metrics,PlatformTransactionManager manager){
        this.db=db;this.redis=redis;this.skus=skus;this.buckets=buckets;this.inventory=inventory;
        this.risk=risk;this.evaluators=evaluators;this.json=json;this.metrics=metrics;
        this.planLoads=new BoundedPlanLoads<>(metrics,64,256);
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        String product=db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        // MySQL shared locks let concurrent reservations proceed; H2 uses a stronger test lock.
        share=product!=null && product.contains("MySQL")?" LOCK IN SHARE MODE":" FOR UPDATE";
    }

    public static String prefix(String session){return "ticket:sale:{"+session+"}:";}
    public Map<String,Object> status(String session){
        Map<String,Object> row=db.one("SELECT * FROM tb_ticket_sale_assembly WHERE session_id=?",session);
        if(row==null){row=new LinkedHashMap<>();row.put("session_id",session);row.put("state","UNMANAGED");row.put("version",0);}
        row.put("cacheReady",cacheReady(row,session));return row;
    }
    private boolean cacheReady(Map<String,Object> state,String session){
        if(!"READY".equals(state.get("state")))return false;
        try{
            long version=Db.number(state,"active_version");
            if(!Long.toString(version).equals(redis.opsForValue().get(prefix(session)+"active-version"))
                || !Long.toString(version).equals(redis.opsForValue().get(prefix(session)+"publication-fence")))return false;
            String payload=redis.opsForValue().get(prefix(session)+"manifest:v"+version);if(payload==null)return false;
            SaleManifest manifest=new SaleManifest(payload,json);
            if(manifest.version()!=version || !session.equals(manifest.sessionId()) || !manifest.digest().equals(state.get("active_digest")))return false;
            for(String sku:manifest.skuIds()){
                List<String> keys=new ArrayList<>();String p=InventoryService.prefix(sku);keys.add(p+"config");keys.add(p+"assembly-fence");
                if(manifest.bucketCount()==16)for(int i=0;i<16;i++)keys.add(p+"stock:bucket:"+i);else keys.add(p+"stock");
                if(!Long.valueOf(keys.size()).equals(redis.countExistingKeys(keys))
                    || !Long.toString(version).equals(redis.opsForHash().get(p+"config","assemblyVersion")))return false;
            }return true;
        }catch(RuntimeException unavailable){return false;}
    }
    public List<Map<String,Object>> history(String session){
        return db.jdbc.queryForList("SELECT session_id,version,request_id,operation,status,digest,operator_id,error,created_at FROM tb_ticket_sale_version WHERE session_id=? ORDER BY version DESC LIMIT 100",session);
    }
    public Map<String,Object> assemble(String session,long expectedVersion,String request,long operator){
        try{return execute(session,expectedVersion,request,operator,false);}finally{invalidatePlans();}
    }
    public Map<String,Object> recover(String session,long expectedVersion,String request,long operator){
        try{return execute(session,expectedVersion,request,operator,true);}finally{invalidatePlans();}
    }

    private Map<String,Object> execute(String session,long expected,String request,long operator,boolean recovering){
        BusinessException.require(request!=null && !request.trim().isEmpty() && request.length()<=80,"装配操作编号无效");
        BusinessException.require(expected>=0,"装配版本无效");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Assembly must not run inside a caller transaction");
        long begin=System.nanoTime();String outcome="failure";
        Map<String,Object> attempt=tx.execute(s->begin(session,expected,request,operator,recovering));
        if(Boolean.TRUE.equals(attempt.get("replayed")))return attempt;
        long version=Db.number(attempt,"version");
        try{
            Map<String,Object> result=tx.execute(s->publish(session,version,request,recovering));
            outcome="success";return result;
        }catch(RuntimeException error){
            String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());
            final String failure=message.substring(0,Math.min(512,message.length()));
            tx.executeWithoutResult(s->{
                db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state='FAILED',error=?,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND version=? AND request_id=? AND state IN ('PREPARING','RECOVERING')",failure,session,version,request);
                db.jdbc.update("UPDATE tb_ticket_sale_version SET status='FAILED',error=? WHERE session_id=? AND version=? AND status IN ('PREPARING','RECOVERING')",failure,session,version);
            });
            throw error;
        }finally{
            metrics.timer("sale.armory.duration","operation",recovering?"recover":"assemble","outcome",outcome)
                .record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);
            metrics.counter("sale.armory.operation","operation",recovering?"recover":"assemble","outcome",outcome).increment();
        }
    }

    private Map<String,Object> begin(String session,long expected,String request,long operator,boolean recovering){
        BusinessException.require(db.one("SELECT id FROM tb_event_session WHERE id=? FOR UPDATE",session)!=null,"场次不存在");
        Map<String,Object> previous=db.one("SELECT * FROM tb_ticket_sale_version WHERE session_id=? AND request_id=?",session,request);
        if(previous!=null){
            BusinessException.require((recovering?"RECOVER":"ASSEMBLE").equals(previous.get("operation"))
                && Db.number(previous,"version")==expected+1 && Db.number(previous,"operator_id")==operator,"操作编号已用于其他参数");
            Map<String,Object> replay=new LinkedHashMap<>(previous);replay.remove("manifest");replay.put("replayed",true);return replay;
        }
        Map<String,Object> state=db.one("SELECT * FROM tb_ticket_sale_assembly WHERE session_id=? FOR UPDATE",session);
        if(state==null){
            BusinessException.require(!recovering && expected==0,"场次尚未纳入装配，不能恢复");
            BusinessException.require(count("SELECT COUNT(*) FROM tb_reserve_request r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id=?",session)==0,"已有交易请求的场次不能在线切换装配模式");
            BusinessException.require(count("SELECT COUNT(*) FROM tb_ticket_sku WHERE event_session_id=? AND sale_start_time<=CURRENT_TIMESTAMP",session)==0,"首次装配必须在开售前完成");
            db.jdbc.update("INSERT INTO tb_ticket_sale_assembly(session_id,state) VALUES(?,'UNPREPARED')",session);
            state=db.one("SELECT * FROM tb_ticket_sale_assembly WHERE session_id=? FOR UPDATE",session);
        }
        BusinessException.require(Db.number(state,"version")==expected,"装配版本冲突");
        Timestamp lease=(Timestamp)state.get("lease_until");
        BusinessException.require(!Arrays.asList("PREPARING","RECOVERING").contains(state.get("state"))
                || lease==null || lease.getTime()<=System.currentTimeMillis(),"装配任务正在执行");
        db.jdbc.update("UPDATE tb_ticket_sale_version SET status='FAILED',error='Previous worker lease expired' WHERE session_id=? AND version=? AND status IN ('PREPARING','RECOVERING')",session,expected);
        long version=expected+1;String phase=recovering?"RECOVERING":"PREPARING";
        db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state=?,version=?,request_id=?,lease_until=?,error='',updated_at=CURRENT_TIMESTAMP WHERE session_id=?",phase,version,request,new Timestamp(System.currentTimeMillis()+120000),session);
        db.jdbc.update("INSERT INTO tb_ticket_sale_version(session_id,version,request_id,operation,status,manifest,operator_id) VALUES(?,?,?,?,?,'',?)",session,version,request,recovering?"RECOVER":"ASSEMBLE",phase,operator);
        Map<String,Object> attempt=new HashMap<>();attempt.put("version",version);return attempt;
    }

    private Map<String,Object> publish(String session,long version,String request,boolean recovering){
        Map<String,Object> state=db.one("SELECT * FROM tb_ticket_sale_assembly WHERE session_id=? FOR UPDATE",session);
        BusinessException.require(state!=null && Db.number(state,"version")==version && request.equals(state.get("request_id"))
                && (recovering?"RECOVERING":"PREPARING").equals(state.get("state")),"装配任务已被新版本取代");
        BusinessException.require(Long.valueOf(1).equals(redis.execute(claimScript,
            Arrays.asList(prefix(session)+"publication-fence",prefix(session)+"active-version"),Long.toString(version),"STRING")),"场次装配任务已被新代次取代");
        Map<String,Object> event=db.one("SELECT e.id event_id,e.name,e.venue,s.starts_at FROM tb_event e JOIN tb_event_session s ON s.event_id=e.id WHERE s.id=?",session);
        BusinessException.require(event!=null,"活动或场次不存在");
        // Drain transactions already modifying orders before rebuilding any Redis marker.
        for(Map<String,Object> order:db.jdbc.queryForList("SELECT o.id FROM tb_ticket_order o JOIN tb_ticket_sku s ON s.id=o.sku_id WHERE s.event_session_id=? ORDER BY o.id",session))
            db.one("SELECT id FROM tb_ticket_order WHERE id=? FOR UPDATE",order.get("id"));
        BusinessException.require(count("SELECT COUNT(*) FROM tb_reserve_request r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id=? AND r.status='REQUESTED'",session)==0,"存在未决预占意图，先完成对账或补偿");
        BusinessException.require(count("SELECT COUNT(*) FROM tb_outbox_message m JOIN tb_ticket_order o ON o.id=m.aggregate_id JOIN tb_ticket_sku s ON s.id=o.sku_id WHERE s.event_session_id=? AND m.message_key LIKE 'RELEASE:%' AND NOT EXISTS (SELECT 1 FROM tb_message_consume_record c WHERE c.consumer_group='ticket-events' AND c.message_key=m.message_key AND c.status='DONE')",session)==0,"存在未完成的库存释放消息");
        List<Map<String,Object>> ids=db.jdbc.queryForList("SELECT id FROM tb_ticket_sku WHERE event_session_id=? ORDER BY id FOR UPDATE",session);
        BusinessException.require(!ids.isEmpty(),"场次没有票档");
        List<TicketSku> catalog=new ArrayList<>();List<Map<String,Object>> metadata=new ArrayList<>();
        for(Map<String,Object> row:ids){
            TicketSku sku=skus.findByIdForUpdate(row.get("id"));validateSku(sku);
            buckets.initializePristine(sku);catalog.add(sku);metadata.add(metadata(sku));
        }
        List<RiskRule> rules=risk.rules();
        // Recovery restores the already-approved rules, never smuggles a policy update into stock repair.
        if(recovering && Db.number(state,"active_version")>0){
            Map<String,Object> old=db.one("SELECT manifest,digest FROM tb_ticket_sale_version WHERE session_id=? AND version=? AND status='READY'",session,state.get("active_version"));
            BusinessException.require(old!=null,"缺少已发布版本，不能推测恢复策略");
            SaleManifest prior=new SaleManifest(old.get("manifest").toString(),json);
            BusinessException.require(prior.digest().equals(old.get("digest")) && prior.digest().equals(state.get("active_digest")),"已发布数据库快照摘要不一致");
            BusinessException.require(new HashSet<>(prior.skuIds()).equals(new HashSet<>(Arrays.asList(catalog.stream().map(TicketSku::getId).toArray(String[]::new)))),"恢复不能增删票档");
            BusinessException.require(prior.bucketCount()==buckets.bucketCount(),"库存拓扑配置与已发布版本不一致");
            for(TicketSku sku:catalog)BusinessException.require(metadata(sku).equals(prior.sku(sku.getId())),"恢复不能同时变更票档配置");
            rules=prior.rules();
        }
        validateRules(rules);
        Map<String,Object> document=new LinkedHashMap<>();document.put("sessionId",session);document.put("version",version);
        document.put("eventId",event.get("event_id"));document.put("eventName",event.get("name"));document.put("venue",event.get("venue"));
        document.put("startsAt",((Timestamp)event.get("starts_at")).getTime());document.put("bucketCount",buckets.bucketCount());
        document.put("skus",metadata);document.put("rules",rules);
        SaleManifest manifest=new SaleManifest(db.json(document),json);
        for(TicketSku sku:catalog){
            inventory.claimAssembly(sku.getId(),version);
            Map<String,Object> snapshot=recovering?recoverySnapshot(sku,version):initialSnapshot(sku);
            inventory.publishAssembly(sku,version,snapshot);
            verifyInventory(sku);verifyMarkers(sku);
        }
        String key=prefix(session)+"manifest:v"+version;
        BusinessException.require(Long.valueOf(1).equals(redis.execute(publishScript,
            Arrays.asList(prefix(session)+"publication-fence",key,prefix(session)+"active-version"),Long.toString(version),manifest.json())),"场次不可变版本冲突或任务已过期");
        BusinessException.require(manifest.json().equals(redis.opsForValue().get(key))
                && Long.toString(version).equals(redis.opsForValue().get(prefix(session)+"active-version")),"版本发布校验失败");
        BusinessException.require(db.jdbc.update("UPDATE tb_ticket_sale_version SET manifest=?,digest=?,status='READY' WHERE session_id=? AND version=? AND status IN ('PREPARING','RECOVERING')",manifest.json(),manifest.digest(),session,version)==1,"版本发布权已失效");
        BusinessException.require(db.jdbc.update("UPDATE tb_ticket_sale_assembly SET state='READY',active_version=?,active_digest=?,lease_until=NULL,error='',updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND version=? AND request_id=? AND state IN ('PREPARING','RECOVERING')",version,manifest.digest(),session,version,request)==1,"装配发布权已失效");
        Map<String,Object> result=status(session);
        BusinessException.require(Boolean.TRUE.equals(result.get("cacheReady")),"发布后的缓存完整性校验失败");
        return result;
    }

    private long count(String sql,Object...args){return db.jdbc.queryForObject(sql,Long.class,args);}
    private void validateSku(TicketSku sku){
        BusinessException.require(sku!=null && sku.getPrice()!=null && sku.getPrice().signum()>0 && sku.getTotalStock()>=0
            && sku.getRemainingStock()>=0 && sku.getRemainingStock()<=sku.getTotalStock() && sku.getPurchaseLimit()>0
            && sku.getSaleStartTime()!=null && sku.getSaleEndTime()!=null && sku.getSaleStartTime().before(sku.getSaleEndTime())
            && sku.getSaleEndTime().getTime()>System.currentTimeMillis()
            && Arrays.asList("ON_SALE","OFF_SALE").contains(sku.getStatus()),"票档配置无效或已停售");
    }
    private Map<String,Object> metadata(TicketSku sku){
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",sku.getId());m.put("price",sku.getPrice().toPlainString());
        m.put("totalStock",sku.getTotalStock());m.put("purchaseLimit",sku.getPurchaseLimit());
        if(inventory.vectorEnabled()){BusinessException.require(sku.getSinglePurchaseLimit()>=1&&sku.getSinglePurchaseLimit()<=100,"单次购票上限无效");m.put("singleLimit",sku.getSinglePurchaseLimit());m.put("reservationProtocol",3);}
        // SKU version also changes on ordinary stock decrements and ON/OFF sale switches.
        // Bind static sale fields, never confuse stock CAS version with assembly version.
        m.put("saleStart",sku.getSaleStartTime().getTime());m.put("saleEnd",sku.getSaleEndTime().getTime());return m;
    }
    private void validateRules(List<RiskRule> rules){
        int policies=0;
        for(RiskRule rule:rules){
            if(!rule.isEnabled())continue;
            BusinessException.require(rule.getWeight()>=0 && rule.getWeight()<=1000 && rule.getConfig()!=null,"规则配置无效");
            Map<String,Object> c=rule.getConfig();
            if("DECISION_POLICY".equals(rule.getRuleCode())){
                policies++;
                for(String name:Arrays.asList("review","block","agentMaxAdjustment","rateLimit"))
                    BusinessException.require(c.get(name) instanceof Number && Double.isFinite(((Number)c.get(name)).doubleValue()),"决策策略字段无效");
                BusinessException.require(((Number)c.get("review")).intValue()>0
                    && ((Number)c.get("block")).intValue()>((Number)c.get("review")).intValue()
                    && ((Number)c.get("agentMaxAdjustment")).intValue()>=0 && ((Number)c.get("rateLimit")).doubleValue()>0,"决策策略阈值无效");
                if(c.containsKey("agentConfidenceThreshold"))BusinessException.require(c.get("agentConfidenceThreshold") instanceof Number
                    && Double.isFinite(((Number)c.get("agentConfidenceThreshold")).doubleValue())
                    && ((Number)c.get("agentConfidenceThreshold")).doubleValue()>=0
                    && ((Number)c.get("agentConfidenceThreshold")).doubleValue()<=1,"置信度阈值无效");
            }else{
                BusinessException.require(evaluators.find(rule).isPresent(),"启用规则没有执行器："+rule.getRuleCode());
                if(c.containsKey("feature"))BusinessException.require(c.get("feature") instanceof String
                    && !((String)c.get("feature")).trim().isEmpty() && c.get("threshold") instanceof Number
                    && Double.isFinite(((Number)c.get("threshold")).doubleValue()),"阈值规则配置无效");
                for(String name:Arrays.asList("minHistory","maxAccountAgeHours"))if(c.containsKey(name))
                    BusinessException.require(c.get(name) instanceof Number && Double.isFinite(((Number)c.get(name)).doubleValue()),"规则前置条件无效");
            }
        }
        BusinessException.require(policies==1,"需要唯一启用的决策策略");
    }

    /** Entry uses a short transaction; reserve revalidates the pinned version inside its own transaction. */
    public SaleManifest admissionPlan(String session,String sku){return tx.execute(s->gate(session,sku));}
    /** V2 entry: one DB publication barrier, cached immutable parsing, no SKU or bucket SUM. */
    public SaleManifest runtimePlan(String session,String sku){return tx.execute(s->runtimeGate(session,sku,null));}

    /**
     * Sprint S1 (multi-level cache, cf. SecKill-ii CacheServiceImpl / 12306 local+distributed cache): a validated plan is
     * reused for at most {@code ticketing.sale.plan-cache-ms} (default 500 ms, 0 = off) by callers that are NOT
     * authoritative (HTTP acceptance and the worker's pre-risk lookup). Every hit still checks Redis active-version and
     * publication-fence (one MGET) and the SKU sale window against the current clock. prepare / ordered reserve / HELD
     * keep calling {@link #runtimeSku}, i.e. the locked MySQL gate, so an Armory change is still caught before stock or
     * order rows are written.
     */
    public SaleManifest cachedRuntimePlan(String session,String sku){
        if(planSingleFlight||planDetachedRedis)return optimizedCachedPlan(session,sku);
        if(planCacheMillis<=0)return runtimePlan(session,sku);
        String key=session+'|'+sku;long now=System.currentTimeMillis();
        CachedPlan hit=planCache.get(key);
        if(hit!=null&&now-hit.at<planCacheMillis){
            String version=Long.toString(hit.plan.version());
            List<String> live=redis.opsForValue().multiGet(Arrays.asList(prefix(session)+"active-version",prefix(session)+"publication-fence"));
            Map<String,Object> item=hit.plan.sku(sku);
            if(live!=null&&live.size()==2&&version.equals(live.get(0))&&version.equals(live.get(1))&&item!=null
                    &&now>=((Number)item.get("saleStart")).longValue()&&now<((Number)item.get("saleEnd")).longValue()){
                metrics.counter("sale.plan.cache","outcome","hit").increment();return hit.plan;
            }
            planCache.remove(key,hit);
        }
        metrics.counter("sale.plan.cache","outcome","miss").increment();
        SaleManifest plan=runtimePlan(session,sku); // failures are never cached
        planCache.put(key,new CachedPlan(plan,now));
        return plan;
    }
    /** Local invalidation on publish/close in this JVM; other nodes rely on the per-hit Redis version check. */
    public void invalidatePlans(){synchronized(planPublication){planGeneration++;planCache.clear();}}
    private static final class CachedPlan{
        final SaleManifest plan;final long at,generation;
        CachedPlan(SaleManifest plan,long at){this(plan,at,0);}
        CachedPlan(SaleManifest plan,long at,long generation){this.plan=plan;this.at=at;this.generation=generation;}
    }
    private final java.util.concurrent.ConcurrentHashMap<String,CachedPlan> planCache=new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${ticketing.sale.plan-cache-ms:500}")
    private long planCacheMillis=500;
    @org.springframework.beans.factory.annotation.Value("${ticketing.sale.plan-single-flight:false}")
    private boolean planSingleFlight;
    @org.springframework.beans.factory.annotation.Value("${ticketing.sale.plan-detached-redis:false}")
    private boolean planDetachedRedis;
    @org.springframework.beans.factory.annotation.Value("${ticketing.sale.plan-load-wait-ms:2000}")
    private long planLoadWaitMillis=2000;
    private final BoundedPlanLoads<CachedPlan> planLoads;
    private final Object planPublication=new Object();
    private volatile long planGeneration;
    private java.util.function.LongSupplier planClock=System::currentTimeMillis;

    private SaleManifest optimizedCachedPlan(String session,String sku){
        // Never claim Redis is outside ALL transactions if a caller already holds a connection.
        if(planCacheMillis<=0||TransactionSynchronizationManager.isActualTransactionActive())return runtimePlan(session,sku);
        String key=session+'|'+sku;CachedPlan hit=planCache.get(key);
        if(hit!=null&&fresh(hit)&&live(hit.plan,session,sku)&&fresh(hit)){
            metrics.counter("sale.plan.cache","outcome","hit").increment();return hit.plan;
        }
        if(hit!=null)planCache.remove(key,hit);
        metrics.counter("sale.plan.cache","outcome","miss").increment();
        long generation=planGeneration;
        java.util.function.Supplier<CachedPlan> load=()->{
            CachedPlan again=planCache.get(key);
            if(again!=null&&fresh(again))return again; // every caller still performs its own Redis check below
            long begin=System.nanoTime(),at=planClock.getAsLong();SaleManifest plan;
            metrics.counter("sale.plan.db.load").increment();
            if(planDetachedRedis){
                Map<String,Object> first=planState(session,"snapshot");
                plan=validateRuntimeSnapshot(session,sku,first,null); // no transaction / borrowed connection here
                long verifiedAt=planClock.getAsLong(); // includes recheck connection wait; never renew TTL after slow I/O
                planState(session,"recheck",state->{
                    requireRuntimeState(state,null);
                    for(String field:Arrays.asList("session_id","state","version","active_version","active_digest","event_status"))
                        BusinessException.require(Objects.equals(first.get(field),state.get(field)),"销售计划在校验期间发生变化");
                });
                at=verifiedAt;
            }else plan=runtimePlan(session,sku);
            CachedPlan value=new CachedPlan(plan,at,generation);
            if(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)>planLoadWaitMillis||!fresh(value))throw planUnavailable();
            synchronized(planPublication){if(generation!=planGeneration)throw planUnavailable();planCache.put(key,value);}
            return value;
        };
        CachedPlan value=planSingleFlight?planLoads.get(key+'|'+generation,load,planLoadWaitMillis):load.get();
        try{if(!fresh(value)||!live(value.plan,session,sku)||!fresh(value))throw planUnavailable();return value.plan;}
        catch(RuntimeException failure){planCache.remove(key,value);throw failure;}
    }
    private boolean fresh(CachedPlan value){long age=planClock.getAsLong()-value.at;return value.generation==planGeneration&&age>=0&&age<planCacheMillis;}
    private boolean live(SaleManifest plan,String session,String sku){
        String version=Long.toString(plan.version());
        List<String> values=redis.opsForValue().multiGet(Arrays.asList(prefix(session)+"active-version",prefix(session)+"publication-fence"));
        Map<String,Object> item=plan.sku(sku);long now=planClock.getAsLong();
        return values!=null&&values.size()==2&&version.equals(values.get(0))&&version.equals(values.get(1))&&item!=null
            &&now>=((Number)item.get("saleStart")).longValue()&&now<((Number)item.get("saleEnd")).longValue();
    }
    private com.ticketing.ticket.AttemptAdmissionException planUnavailable(){return new com.ticketing.ticket.AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","销售计划校验暂不可用，请使用原请求编号稍后重试");}
    private Map<String,Object> planState(String session,String phase){return planState(session,phase,state->requireRuntimeState(state,null));}
    private Map<String,Object> planState(String session,String phase,java.util.function.Consumer<Map<String,Object>> check){
        long begin=System.nanoTime();String outcome="failure";
        try{Map<String,Object> value=tx.execute(status->{
            long body=System.nanoTime();String bodyOutcome="failure";
            try{Map<String,Object> state=readRuntimeState(session);check.accept(state);
                // JdbcTemplate column maps are case-insensitive (notably H2 aliases). Preserve that contract.
                Map<String,Object> copy=new org.springframework.util.LinkedCaseInsensitiveMap<>();copy.putAll(state);bodyOutcome="success";return copy;}
            finally{metrics.timer("sale.plan.db.duration","read",phase,"phase","body","outcome",bodyOutcome).record(System.nanoTime()-body,TimeUnit.NANOSECONDS);}
        });outcome="success";return value;}
        finally{metrics.timer("sale.plan.db.duration","read",phase,"phase","total","outcome",outcome).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
    /** Joins the caller's stock transaction, pinning the assembly row until its commit. */
    public TicketSku runtimeSku(String session,String sku,long version){
        SaleManifest plan=runtimeGate(session,sku,version);
        TicketSku current=skus.findStaticById(sku);
        BusinessException.require(current!=null && session.equals(current.getEventSessionId())
                && metadata(current).equals(plan.sku(sku)) && "ON_SALE".equals(current.getStatus()),"票档配置已变更");
        if(!buckets.active(sku))buckets.configure(current); // once per instance/SKU after restart
        return current;
    }
    private SaleManifest runtimeGate(String session,String sku,Long expected){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sale gate requires transaction");
        return validateRuntimeSnapshot(session,sku,readRuntimeState(session),expected);
    }
    private Map<String,Object> readRuntimeState(String session){
        return db.one("SELECT a.*,e.status event_status FROM tb_ticket_sale_assembly a "
                +"JOIN tb_event_session s ON s.id=a.session_id JOIN tb_event e ON e.id=s.event_id WHERE a.session_id=?"+share,session);
    }
    private long requireRuntimeState(Map<String,Object> state,Long expected){
        BusinessException.require(state!=null && "READY".equals(state.get("state"))
                && "ON_SALE".equals(state.get("event_status")),"场次未就绪");
        long version=Db.number(state,"active_version");
        BusinessException.require(version==Db.number(state,"version") && (expected==null || expected==version),"装配版本已变更");
        return version;
    }
    private SaleManifest validateRuntimeSnapshot(String session,String sku,Map<String,Object> state,Long expected){
        long version=requireRuntimeState(state,expected);
        List<String> values=redis.opsForValue().multiGet(Arrays.asList(prefix(session)+"active-version",
                prefix(session)+"publication-fence",prefix(session)+"manifest:v"+version));
        BusinessException.require(values!=null && values.size()==3 && Long.toString(version).equals(values.get(0))
                && Long.toString(version).equals(values.get(1)),"场次缓存版本未就绪");
        SaleManifest plan=parsedManifests.get(session,version,String.valueOf(state.get("active_digest")),values.get(2),json);
        Map<String,Object> item=plan.sku(sku);
        BusinessException.require(item!=null && plan.bucketCount()==buckets.bucketCount(),"票档或库存拓扑不匹配");
        long now=System.currentTimeMillis();
        BusinessException.require(now>=((Number)item.get("saleStart")).longValue() && now<((Number)item.get("saleEnd")).longValue(),"票档未到开售时间或已停售");
        String p=InventoryService.prefix(sku);
        List<String> keys=new ArrayList<>();
        if(plan.bucketCount()==16)for(int i=0;i<16;i++)keys.add(p+"stock:bucket:"+i);else keys.add(p+"stock");
        keys.add(p+"config");keys.add(p+"assembly-fence");
        BusinessException.require(Long.valueOf(keys.size()).equals(redis.countExistingKeys(keys))
                && Long.toString(version).equals(redis.opsForValue().get(p+"assembly-fence")),"库存缓存不完整，需显式恢复");
        Map<Object,Object> c=redis.opsForHash().entries(p+"config");
        if(inventory.vectorEnabled())BusinessException.require("3".equals(c.get("reservationProtocol"))&&item.get("singleLimit")!=null&&item.get("singleLimit").toString().equals(c.get("singleLimit")),"跨桶协议/单次限额版本不一致");
        BusinessException.require("ON_SALE".equals(c.get("status")) && Long.toString(version).equals(c.get("assemblyVersion"))
                && item.get("purchaseLimit").toString().equals(c.get("limit"))
                && item.get("saleStart").toString().equals(c.get("start")) && item.get("saleEnd").toString().equals(c.get("end")),"库存配置版本不一致");
        return plan;
    }
    public void assertCanCreate(String session,String sku){gate(session,sku);}
    public void assertCanReserve(String session,String sku,Map<String,Object> admission){
        SaleManifest plan=gate(session,sku);if(plan==null)return;
        BusinessException.require(admission!=null && admission.get("saleVersion") instanceof Number
            && ((Number)admission.get("saleVersion")).longValue()==plan.version(),"装配版本已切换，请使用新请求重试");
    }
    private SaleManifest gate(String session,String skuId){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sale gate requires transaction");
        Map<String,Object> state=db.one("SELECT * FROM tb_ticket_sale_assembly WHERE session_id=?"+share,session);
        if(state==null)return null; // Explicit legacy compatibility; all nodes must enable the gate before enrollment.
        BusinessException.require("READY".equals(state.get("state")) && Db.number(state,"active_version")==Db.number(state,"version"),"场次装配未就绪");
        long version=Db.number(state,"active_version");
        BusinessException.require(Long.toString(version).equals(redis.opsForValue().get(prefix(session)+"active-version"))
            && Long.toString(version).equals(redis.opsForValue().get(prefix(session)+"publication-fence")),"场次缓存版本未就绪");
        String payload=redis.opsForValue().get(prefix(session)+"manifest:v"+version);
        BusinessException.require(payload!=null,"场次装配快照缺失，需显式恢复");
        SaleManifest plan=parsedManifests.get(session,version,String.valueOf(state.get("active_digest")),payload,json);
        BusinessException.require(plan.version()==version && session.equals(plan.sessionId())
            && plan.digest().equals(state.get("active_digest")) && plan.bucketCount()==buckets.bucketCount(),"场次快照或库存拓扑不一致");
        TicketSku sku=skus.findById(skuId);
        BusinessException.require(sku!=null && session.equals(sku.getEventSessionId()) && metadata(sku).equals(plan.sku(skuId)),"票档与装配版本不一致");
        BusinessException.require("ON_SALE".equals(sku.getStatus()) && sku.getSaleStartTime().getTime()<=System.currentTimeMillis()
            && sku.getSaleEndTime().getTime()>System.currentTimeMillis(),"票档未到开售时间或已停售");
        BusinessException.require(count("SELECT COUNT(*) FROM tb_event e JOIN tb_event_session s ON s.event_id=e.id WHERE s.id=? AND e.status='ON_SALE'",session)==1,"活动不可售");
        if(buckets.bucketCount()==16 && !buckets.active(skuId)) buckets.configure(sku); // Restore instance-local topology once after restart.
        String p=InventoryService.prefix(skuId);
        List<String> stockKeys=new ArrayList<>();
        if(plan.bucketCount()==16)for(int i=0;i<16;i++)stockKeys.add(p+"stock:bucket:"+i);else stockKeys.add(p+"stock");
        stockKeys.add(p+"config");stockKeys.add(p+"assembly-fence");
        BusinessException.require(Long.valueOf(stockKeys.size()).equals(redis.countExistingKeys(stockKeys)),"交易库存缓存缺失，需显式恢复");
        BusinessException.require(Long.toString(version).equals(redis.opsForHash().get(p+"config","assemblyVersion"))
            && Long.toString(version).equals(redis.opsForValue().get(p+"assembly-fence")),"库存装配代次不一致");
        verifyConfiguration(sku);
        return plan;
    }

    private void verifyInventory(TicketSku sku){
        String p=InventoryService.prefix(sku.getId());
        if(buckets.bucketCount()==16){
            for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_ticket_stock_bucket WHERE sku_id=? ORDER BY bucket_id",sku.getId()))
                requireStock(p+"stock:bucket:"+Db.number(row,"bucket_id"),Db.number(row,"remaining_stock"));
        }else requireStock(p+"stock",sku.getRemainingStock());
        verifyConfiguration(sku);
    }
    private void verifyConfiguration(TicketSku sku){
        Map<Object,Object> config=redis.opsForHash().entries(InventoryService.prefix(sku.getId())+"config");
        if(inventory.vectorEnabled())BusinessException.require("3".equals(config.get("reservationProtocol"))&&Integer.toString(sku.getSinglePurchaseLimit()).equals(config.get("singleLimit")),"跨桶协议或单次限额未装配");
        BusinessException.require(Objects.equals(config.get("status"),sku.getStatus())
            && Objects.equals(config.get("limit"),Integer.toString(sku.getPurchaseLimit()))
            && Objects.equals(config.get("start"),Long.toString(sku.getSaleStartTime().getTime()))
            && Objects.equals(config.get("end"),Long.toString(sku.getSaleEndTime().getTime())),"库存配置装配不完整");
    }
    private void verifyMarkers(TicketSku sku){
        String p=InventoryService.prefix(sku.getId());
        for(Map<String,Object> user:db.jdbc.queryForList("SELECT user_id,quantity FROM tb_user_purchase WHERE sku_id=?",sku.getId())){
            String cached=redis.opsForValue().get(p+"user:"+user.get("user_id"));long quantity=Db.number(user,"quantity");
            BusinessException.require(quantity>=0 && (quantity==0 && cached==null || Long.toString(quantity).equals(cached)),"用户限购缓存不完整，需恢复");
        }
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT r.id,r.quantity,r.user_id,i.fingerprint FROM tb_reservation r JOIN tb_reserve_request i ON i.request_id=r.request_id WHERE r.sku_id=? AND r.status='HELD'",sku.getId())){
            Map<Object,Object> marker=redis.opsForHash().entries(p+"reservation:"+row.get("id"));
            BusinessException.require("HELD".equals(marker.get("state")) && row.get("fingerprint").equals(marker.get("fingerprint"))
                && row.get("quantity").toString().equals(marker.get("quantity")) && row.get("user_id").toString().equals(marker.get("userId")),"预订幂等缓存不完整，需恢复");
            if(buckets.bucketCount()==16){
                Map<String,Object> allocation=db.one("SELECT * FROM tb_stock_bucket_allocation WHERE reservation_id=? AND released=false",row.get("id"));
                if(allocation!=null&&Db.number(allocation,"bucket_id")==-1){BusinessException.require("3".equals(marker.get("protocolVersion"))&&buckets.allocation(allocation).equals(InventoryService.decodeAllocation(String.valueOf(marker.get("allocations")),(int)Db.number(row,"quantity"))),"跨桶预占缓存与分配明细不一致");continue;}
                BusinessException.require(allocation!=null && allocation.get("bucket_id").toString().equals(marker.get("bucketId"))
                    && (p+"stock:bucket:"+allocation.get("bucket_id")).equals(marker.get("stockKey")),"预订库存桶缓存不一致");
            }
        }
    }

    private void requireStock(String key,long expected){
        BusinessException.require(Long.toString(expected).equals(redis.opsForValue().get(key)),"Redis 与权威库存不一致："+key);
    }

    /** Only called behind a persisted RECOVERING barrier, with all existing order and SKU locks held. */
    private Map<String,Object> recoverySnapshot(TicketSku sku,long version){
        String id=sku.getId(),p=InventoryService.prefix(id);
        BusinessException.require(count("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",id)<=10000,"在线恢复请求数量超过安全上限");
        BusinessException.require(count("SELECT COUNT(*) FROM tb_reserve_request i LEFT JOIN tb_reservation r ON r.request_id=i.request_id LEFT JOIN tb_ticket_order o ON o.id=i.order_id AND o.reservation_id=r.id WHERE i.sku_id=? AND i.status='COMPLETED' AND (r.id IS NULL OR o.id IS NULL)",id)==0,"已完成请求缺少预订或订单，不能恢复");
        BusinessException.require(count("SELECT COUNT(*) FROM tb_reservation r LEFT JOIN tb_ticket_order o ON o.reservation_id=r.id WHERE r.sku_id=? AND r.status='HELD' AND (o.id IS NULL OR o.status IN ('CANCELLED','REFUNDED','PAY_TIMEOUT','RISK_BLOCKED'))",id)==0,"预订与订单状态不一致，不能恢复");
        List<Map<String,Object>> held=db.jdbc.queryForList("SELECT r.*,i.fingerprint FROM tb_reservation r JOIN tb_reserve_request i ON i.request_id=r.request_id WHERE r.sku_id=? AND r.status='HELD' ORDER BY r.id FOR UPDATE",id);
        long heldCount=count("SELECT COUNT(*) FROM tb_reservation WHERE sku_id=? AND status='HELD'",id);
        BusinessException.require(heldCount==held.size(),"预订缺少请求指纹，不能安全恢复");
        Map<String,Long> users=new HashMap<>();Map<String,Long> consumed=new HashMap<>();long sold=0;
        for(Map<String,Object> r:held){
            long quantity=Db.number(r,"quantity");sold+=quantity;users.merge(r.get("user_id").toString(),quantity,Long::sum);
            if(buckets.bucketCount()==16){
                Map<String,Object> allocation=db.one("SELECT * FROM tb_stock_bucket_allocation WHERE reservation_id=? AND released=false",r.get("id"));
                BusinessException.require(allocation!=null && id.equals(allocation.get("sku_id")) && Db.number(allocation,"quantity")==quantity,"预订缺少有效库存桶分配");
                r.put("bucket_id",allocation.get("bucket_id"));buckets.allocation(allocation).forEach((bucket,n)->consumed.merge(bucket.toString(),n.longValue(),Long::sum));
            }
        }
        if(buckets.bucketCount()==16)BusinessException.require(count("SELECT COUNT(*) FROM tb_stock_bucket_allocation WHERE sku_id=? AND released=false",id)==heldCount,"存在孤立库存桶分配，不能恢复");
        Map<String,Long> persistedUsers=new HashMap<>();
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_user_purchase WHERE sku_id=?",id)){
            long quantity=Db.number(row,"quantity");BusinessException.require(quantity>=0,"用户限购计数异常");
            if(quantity>0)persistedUsers.put(row.get("user_id").toString(),quantity);
        }
        BusinessException.require(users.equals(persistedUsers),"用户限购与有效预订不一致，不能恢复");
        Map<String,Long> stocks=new LinkedHashMap<>();
        if(buckets.bucketCount()==16){
            for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_ticket_stock_bucket WHERE sku_id=? ORDER BY bucket_id",id)){
                String bucket=row.get("bucket_id").toString();
                BusinessException.require(Db.number(row,"total_stock")-Db.number(row,"remaining_stock")==consumed.getOrDefault(bucket,0L),"库存桶分配与余额不一致");
                stocks.put(p+"stock:bucket:"+bucket,Db.number(row,"remaining_stock"));
            }
        }else{
            BusinessException.require(sku.getTotalStock()-sku.getRemainingStock()==sold,"库存与有效预订不一致");
            stocks.put(p+"stock",(long)sku.getRemainingStock());
        }
        List<String> keys=redis.execute((RedisCallback<List<String>>)connection->{
            List<String> found=new ArrayList<>();int scanned=0;
            try(Cursor<byte[]> cursor=connection.scan(ScanOptions.scanOptions().match(p+"*").count(500).build())){
                while(cursor.hasNext()){
                    BusinessException.require(++scanned<=50000,"在线恢复扫描数量超过安全上限");
                    String key=new String(cursor.next(),java.nio.charset.StandardCharsets.UTF_8);
                    if(key.equals(p+"config") || key.equals(p+"stock") || key.startsWith(p+"stock:bucket:")
                        || key.startsWith(p+"user:") || key.startsWith(p+"reservation:") || key.equals(p+"pending"))found.add(key);
                }
            }return found;
        });
        BusinessException.require(keys==null || keys.size()<=30032,"在线恢复 Key 数量超过安全上限");
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("replace",true);
        snapshot.put("cleanup",keys==null?Collections.emptyList():keys);
        Map<String,String> stockValues=new LinkedHashMap<>(),userValues=new LinkedHashMap<>();
        stocks.forEach((key,value)->stockValues.put(key,value.toString()));
        users.forEach((user,value)->userValues.put(p+"user:"+user,value.toString()));
        Map<String,Map<String,String>> markers=new LinkedHashMap<>();List<Map<String,Object>> pending=new ArrayList<>();
        snapshot.put("stocks",stockValues);snapshot.put("users",userValues);snapshot.put("markers",markers);snapshot.put("pending",pending);
        BusinessException.require(count("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",id)<=10000,"在线恢复请求数量超过安全上限");
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_reserve_request WHERE sku_id=? ORDER BY request_id",id)){
            Map<String,Object> reservation=db.one("SELECT * FROM tb_reservation WHERE request_id=?",row.get("request_id"));
            boolean active=reservation!=null && "HELD".equals(reservation.get("status"));
            Map<String,String> marker=new LinkedHashMap<>();marker.put("fingerprint",row.get("fingerprint").toString());
            marker.put("quantity",row.get("quantity").toString());marker.put("userId",row.get("user_id").toString());marker.put("state",active?"HELD":"RELEASED");
            String stockKey=p+"stock";
            if(active && buckets.bucketCount()==16){
                Map<String,Object> allocation=db.one("SELECT * FROM tb_stock_bucket_allocation WHERE reservation_id=?",row.get("reservation_id"));
                marker.put("bucketId",allocation.get("bucket_id").toString());stockKey=p+"stock:bucket:"+allocation.get("bucket_id");
                if(Db.number(allocation,"bucket_id")==-1){marker.put("protocolVersion","3");marker.put("allocations",buckets.vectorJson(allocation));stockKey="VECTOR";}
            }
            marker.put("stockKey",stockKey);
            if(reservationsV2 && reservation!=null && count("SELECT COUNT(*) FROM tb_reservation_job WHERE reservation_id=? AND state='RESERVED'",row.get("reservation_id"))==1){
                long expires=((Timestamp)reservation.get("expires_at")).getTime();
                marker.putIfAbsent("protocolVersion","2");marker.put("assemblyVersion",Long.toString(version));
                marker.put("heldAt",Long.toString(expires-600000));marker.put("expiresAt",Long.toString(expires));
            }
            markers.put(p+"reservation:"+row.get("reservation_id"),marker);
            if(active){Map<String,Object> entry=new LinkedHashMap<>();entry.put("member",row.get("reservation_id").toString());entry.put("score",((Timestamp)reservation.get("expires_at")).getTime());pending.add(entry);}
        }
        return snapshot;
    }
    private Map<String,Object> initialSnapshot(TicketSku sku){
        Map<String,Object> snapshot=new LinkedHashMap<>();Map<String,String> stocks=new LinkedHashMap<>();String p=InventoryService.prefix(sku.getId());
        if(buckets.bucketCount()==16){
            for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_ticket_stock_bucket WHERE sku_id=? ORDER BY bucket_id",sku.getId()))
                stocks.put(p+"stock:bucket:"+row.get("bucket_id"),Long.toString(Db.number(row,"remaining_stock")));
        }else stocks.put(p+"stock",Integer.toString(sku.getRemainingStock()));
        // Ordinary assembly never repairs a lost trading key; only explicit recovery may replace it.
        long requests=count("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",sku.getId());
        for(String key:stocks.keySet())if(!Boolean.TRUE.equals(redis.hasKey(key)))
            BusinessException.require(requests==0 && sku.getSaleStartTime().getTime()>System.currentTimeMillis()
                && sku.getRemainingStock()==sku.getTotalStock(),"开售后或已有请求的库存缺失，必须显式恢复");
        if(requests==0)BusinessException.require(sku.getRemainingStock()==sku.getTotalStock(),"首次库存与初始总量不一致，不能推测初始化");
        snapshot.put("replace",false);snapshot.put("stocks",stocks);snapshot.put("users",Collections.emptyMap());
        snapshot.put("markers",Collections.emptyMap());snapshot.put("pending",Collections.emptyList());snapshot.put("cleanup",Collections.emptyList());return snapshot;
    }
}
