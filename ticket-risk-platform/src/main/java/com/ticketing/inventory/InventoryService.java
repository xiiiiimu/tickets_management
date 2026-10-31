package com.ticketing.inventory;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.ticket.ReservationIntent;
import com.ticketing.ticket.TicketSku;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryService {
    private final StringRedisTemplate redis;
    private final TicketInventoryRepository skus;
    private final ReservationIntentRepository intents;
    private final MeterRegistry metrics;
    private final org.springframework.transaction.support.TransactionTemplate configurationTx;
    private final org.springframework.transaction.support.TransactionTemplate publicationTx;
    private final DefaultRedisScript<Long> reserve = script("reserve_ticket"), release = script("release_ticket");
    private final DefaultRedisScript<Long> bucketReserve = script("reserve_ticket_bucket");
    private final DefaultRedisScript<Long> abortRuntime = script("abort_reservation_v2");
    private final DefaultRedisScript<Long> vectorReserve=script("reserve_ticket_vector"),vectorRelease=script("release_ticket_vector"),soldOut=script("mark_sold_out_attempt");
    @org.springframework.beans.factory.annotation.Value("${ticketing.inventory.vector-reservations:false}") private boolean vectorReservations;
    public boolean vectorEnabled(){return vectorReservations;}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.core.env.Environment environment;
    @javax.annotation.PostConstruct public void validateVectorMode(){if(vectorReservations&&(environment==null||!environment.getProperty("ticketing.reservations-v2.enabled",Boolean.class,false)||!environment.getProperty("ticketing.reservations-v2.attempts.enabled",Boolean.class,false)||!environment.getProperty("ticketing.reservations-v2.attempts.ordered-lua",Boolean.class,false)))throw new IllegalStateException("Vector inventory requires the durable ordered attempt path");}
    private final DefaultRedisScript<Long> assemblyClaim = script("sale_assembly_claim");
    private final DefaultRedisScript<Long> assemblyPublish = script("sale_inventory_publish");
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockBucketService buckets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private Db assemblyDb;
    @org.springframework.beans.factory.annotation.Value("${ticketing.sale.armory.enabled:false}")
    private boolean armoryEnabled;
    /** Per-SKU single-flight for cold Redis init only (single-instance). */
    private final ConcurrentHashMap<String, Object> initLocks = new ConcurrentHashMap<>();

    public InventoryService(StringRedisTemplate redis, TicketInventoryRepository skus,
                            ReservationIntentRepository intents, MeterRegistry metrics,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.redis = redis;
        this.skus = skus;
        this.intents = intents;
        this.metrics = metrics;
        this.configurationTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.publicationTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.publicationTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/" + name + ".lua"));
        s.setResultType(Long.class);
        return s;
    }

    public static String prefix(String sku) {
        return "ticket:{" + sku + "}:";
    }

    /**
     * Full Redis inventory/config refresh under SKU row lock.
     * Intended for publish / admin / scheduled cache refresh / cold init — not every reserve.
     */
    private java.util.function.Consumer<Runnable> afterCommitExecutor = Runnable::run;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPublicationExecutor(com.ticketing.infrastructure.TaskPools pools) {
        afterCommitExecutor = task -> pools.execute("cache", task);
    }

    public void configure(String skuId) {
        boolean outerTransaction = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        // Explicit transaction also covers ensureReady's same-instance invocation.
        configurationTx.executeWithoutResult(status -> {
            TicketSku sku = skus.findByIdForUpdate(skuId);
            BusinessException.require(sku != null, "票档不存在");
            if (buckets != null) buckets.configure(sku);
            if (outerTransaction) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            // Do not acquire another pooled connection while holding the committing one.
                            try { afterCommitExecutor.accept(() -> publishLatest(skuId)); }
                            catch (RuntimeException error) { publicationFailed(skuId, error); }
                        }
                    });
            }
        });
        // The owned transaction has released its connection before publication starts.
        if (!outerTransaction) publishLatest(skuId);
    }

    private void publishLatest(String skuId) {
        try {
            publicationTx.executeWithoutResult(status -> {
                // Concurrent callbacks serialize and read the latest committed sale configuration.
                TicketSku latest = skus.findByIdForUpdate(skuId);
                BusinessException.require(latest != null, "票档不存在");
                writeRedisFromSku(latest);
            });
        } catch (RuntimeException error) { publicationFailed(skuId, error); }
    }

    private void publicationFailed(String skuId, RuntimeException error) {
        metrics.counter("inventory.config.publish.failure").increment();
        org.slf4j.LoggerFactory.getLogger(InventoryService.class)
            .warn("Committed SKU config publication failed; scheduled refresh will retry sku={}", skuId, error);
    }

    /**
     * Hot-path readiness: if Redis config+stock already present, return without DB lock.
     * Only missing keys enter {@link #configure(String)} (FOR UPDATE) under per-SKU single-flight.
     */
    public void ensureReady(String skuId) {
        if (redisReady(skuId)) {
            return;
        }
        Object lock = initLocks.computeIfAbsent(skuId, id -> new Object());
        synchronized (lock) {
            if (redisReady(skuId)) {
                return;
            }
            configure(skuId);
            BusinessException.require(redisReady(skuId), "库存缓存未就绪，请稍后重试");
        }
    }

    boolean redisReady(String skuId) {
        String p = prefix(skuId);
        if(buckets!=null && buckets.active(skuId)) {
            if(!Boolean.TRUE.equals(redis.hasKey(p+"config"))) return false;
            // All bucket keys must exist: missing inventory is not silently recreated after trading.
            List<String> keys=new ArrayList<>();
            for(int i=0;i<16;i++) keys.add(p+"stock:bucket:"+i);
            return Long.valueOf(16).equals(redis.countExistingKeys(keys));
        }
        return Boolean.TRUE.equals(redis.hasKey(p + "config"))
                && Boolean.TRUE.equals(redis.hasKey(p + "stock"));
    }

    private void writeRedisFromSku(TicketSku sku) {
        String p = prefix(sku.getId());
        List<Map<String,Object>> bucketRows=buckets==null ? Collections.emptyList() : buckets.configure(sku);
        Map<String, String> config = new HashMap<>();
        config.put("status", sku.getStatus());
        config.put("limit", Integer.toString(sku.getPurchaseLimit()));
        config.put("start", String.valueOf(sku.getSaleStartTime().getTime()));
        config.put("end", String.valueOf(sku.getSaleEndTime().getTime()));
        if(vectorReservations){config.put("singleLimit",Integer.toString(sku.getSinglePurchaseLimit()));config.put("reservationProtocol","3");}
        redis.opsForHash().putAll(p + "config", config);
        if(!bucketRows.isEmpty()) {
            boolean pristine=mayInitialize(sku);
            if(pristine) for(Map<String,Object> row:bucketRows) {
                redis.opsForValue().setIfAbsent(p+"stock:bucket:"+Db.number(row,"bucket_id"),String.valueOf(Db.number(row,"remaining_stock")));
            }
            return;
        }
        // Only pristine SKUs can initialize. Lost trading keys require reconciliation, never a blind refill.
        if (mayInitialize(sku))
            redis.opsForValue().setIfAbsent(p + "stock", Integer.toString(sku.getRemainingStock()));
    }

    private boolean mayInitialize(TicketSku sku) {
        if(sku.getRemainingStock()!=sku.getTotalStock() || intents.countBySkuId(sku.getId())!=0)return false;
        if(armoryEnabled && assemblyDb!=null && sku.getSaleStartTime().getTime()<=System.currentTimeMillis()) {
            Long managed=assemblyDb.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_sale_assembly a JOIN tb_ticket_sku s ON s.event_session_id=a.session_id WHERE s.id=?",Long.class,sku.getId());
            if(managed!=null && managed>0)return false;
        }
        return true;
    }

    /** Persistent Redis fencing also protects against a worker losing its DB connection mid-publication. */
    public void claimAssembly(String sku, long version) {
        String p=prefix(sku);
        Long claimed=redis.execute(assemblyClaim,Arrays.asList(p+"assembly-fence",p+"config"),Long.toString(version),"HASH");
        BusinessException.require(Long.valueOf(1).equals(claimed),"库存装配任务已被新代次取代");
    }

    @SuppressWarnings("unchecked")
    public void publishAssembly(TicketSku sku,long version,Map<String,Object> snapshot) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Assembly publication requires a transaction and admission barrier");
        String p=prefix(sku.getId());
        Map<String,String> config=new LinkedHashMap<>();config.put("status",sku.getStatus());
        config.put("limit",Integer.toString(sku.getPurchaseLimit()));
        config.put("start",Long.toString(sku.getSaleStartTime().getTime()));config.put("end",Long.toString(sku.getSaleEndTime().getTime()));
        if(vectorReservations){config.put("singleLimit",Integer.toString(sku.getSinglePurchaseLimit()));config.put("reservationProtocol","3");}
        snapshot.put("config",config);
        Set<String> keys=new LinkedHashSet<>(Arrays.asList(p+"assembly-fence",p+"config",p+"pending"));
        for(String group:Arrays.asList("stocks","users","markers"))keys.addAll(((Map<String,?>)snapshot.get(group)).keySet());
        keys.addAll((List<String>)snapshot.get("cleanup"));
        for(String key:keys)BusinessException.require(key.startsWith(p),"装配 Key 不属于当前 SKU");
        try {
            String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot);
            Long published=redis.execute(assemblyPublish,new ArrayList<>(keys),Long.toString(version),payload);
            BusinessException.require(Long.valueOf(1).equals(published),"库存装配发布已过期");
        } catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalArgumentException(error);}
    }

    public long reserve(ReservationIntent request) {
        String p = prefix(request.getSkuId());
        long begin = System.nanoTime();
        try {
            if(buckets!=null && buckets.active(request.getSkuId())) {
                List<String> keys=new ArrayList<>();keys.add(p+"config");
                for(int i=0;i<16;i++) keys.add(p+"stock:bucket:"+i);
                keys.add(p+"user:"+request.getUserId());keys.add(p+"reservation:"+request.getReservationId());keys.add(p+"pending");
                int hash=request.getRequestId().hashCode();
                int preferred=Math.floorMod(hash^(hash>>>16),16);
                Long selected=redis.execute(bucketReserve,keys,request.getFingerprint(),String.valueOf(request.getQuantity()),
                        request.getReservationId(),String.valueOf(System.currentTimeMillis()+900000),String.valueOf(request.getUserId()),String.valueOf(preferred));
                if(selected!=null && selected>=100) {
                    request.setInventoryBucket(selected.intValue()-100);
                    return 1;
                }
                return selected==null ? -9 : selected;
            }
            Long result = redis.execute(reserve,
                    Arrays.asList(p + "config", p + "stock", p + "user:" + request.getUserId(),
                            p + "reservation:" + request.getReservationId(), p + "pending"),
                    request.getFingerprint(),
                    Integer.toString(request.getQuantity()),
                    request.getReservationId(),
                    String.valueOf(System.currentTimeMillis() + 900000),
                    Long.toString(request.getUserId()));
            return result == null ? -9 : result;
        } finally {
            metrics.timer("redis.operation.latency").record(System.nanoTime() - begin, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private List<String> runtimeKeys(ReservationIntent r,boolean reserving){
        BusinessException.require(buckets!=null && buckets.bucketCount()==16 && (!reserving||buckets.active(r.getSkuId())),"V2 必须使用已装配的16桶库存");
        String p=prefix(r.getSkuId());List<String> keys=new ArrayList<>();keys.add(p+"config");
        for(int i=0;i<16;i++)keys.add(p+"stock:bucket:"+i);
        keys.add(p+"user:"+r.getUserId());keys.add(p+"reservation:"+r.getReservationId());keys.add(p+"pending");keys.add(p+"assembly-fence");return keys;
    }
    public boolean hasRuntimeHold(ReservationIntent r){
        String key=prefix(r.getSkuId())+"reservation:"+r.getReservationId();
        return "HELD".equals(redis.opsForHash().get(key,"state")) && r.getFingerprint().equals(redis.opsForHash().get(key,"fingerprint"));
    }
    public long reserveRuntime(ReservationIntent r,long version,long queueDeadline){
        long begin=System.nanoTime();
        try{
            int hash=r.getRequestId().hashCode();
            Long result=redis.execute(vectorReservations?vectorReserve:bucketReserve,runtimeKeys(r,true),r.getFingerprint(),Integer.toString(r.getQuantity()),r.getReservationId(),"0",
                    Long.toString(r.getUserId()),Integer.toString(Math.floorMod(hash^(hash>>>16),16)),Long.toString(version),Long.toString(queueDeadline));
            if(vectorReservations&&Long.valueOf(1).equals(result)){loadRuntimeHold(r,version);return 1;}
            if(result!=null && result>=100){
                r.setInventoryBucket(result.intValue()-100);
                Object expiry=redis.opsForHash().get(prefix(r.getSkuId())+"reservation:"+r.getReservationId(),"expiresAt");
                BusinessException.require(expiry!=null,"原子预占期限缺失");r.setExpiresAt(new java.sql.Timestamp(Long.parseLong(expiry.toString())));
            }
            return result==null?-9:result;
        }finally{metrics.timer("reservation.v2.lua").record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    public void abortRuntime(ReservationIntent r,long version){
        Object protocol=redis.opsForHash().get(prefix(r.getSkuId())+"reservation:"+r.getReservationId(),"protocolVersion");
        boolean vector=vectorReservations||"3".equals(protocol);
        // Existing committed protocol-2 orders remain releasable during and after rollout.
        if("2".equals(protocol))vector=false;
        Long result=vector?redis.execute(vectorRelease,runtimeKeys(r,false),r.getFingerprint(),r.getReservationId(),Long.toString(version),"ABORT")
            :redis.execute(abortRuntime,runtimeKeys(r,false),r.getFingerprint(),r.getReservationId(),Long.toString(version));
        BusinessException.require(result!=null&&result>=0,"库存补偿需要显式恢复");
    }
    /** Read a prior ordered Lua result; never re-execute reserve outside its durable SKU turn. */
    public void loadRuntimeHold(ReservationIntent r,long version){
        Map<Object,Object> marker=redis.opsForHash().entries(prefix(r.getSkuId())+"reservation:"+r.getReservationId());
        BusinessException.require("HELD".equals(marker.get("state"))&&r.getFingerprint().equals(marker.get("fingerprint"))
            &&Arrays.asList("2","3").contains(marker.get("protocolVersion"))&&Long.toString(version).equals(marker.get("assemblyVersion"))
            &&Integer.toString(r.getQuantity()).equals(marker.get("quantity"))&&Long.toString(r.getUserId()).equals(marker.get("userId")),"有序预占结果缺失或不匹配，保留意图等待恢复");
        if("3".equals(marker.get("protocolVersion"))){
            Map<Integer,Integer> allocation=decodeAllocation(String.valueOf(marker.get("allocations")),r.getQuantity());
            r.setInventoryAllocation(allocation);r.setInventoryBucket(-1);r.setExpiresAt(new java.sql.Timestamp(Long.parseLong(String.valueOf(marker.get("expiresAt")))));return;
        }
        r.setInventoryAllocation(null);int bucket=Integer.parseInt(String.valueOf(marker.get("bucketId")));
        BusinessException.require(bucket>=0&&bucket<16&&(prefix(r.getSkuId())+"stock:bucket:"+bucket).equals(marker.get("stockKey")),"有序预占桶不匹配");
        r.setInventoryBucket(bucket);r.setExpiresAt(new java.sql.Timestamp(Long.parseLong(String.valueOf(marker.get("expiresAt")))));
    }
    public void release(String sku, Object user, String reservation) {
        String p = prefix(sku);
        if("3".equals(redis.opsForHash().get(p+"reservation:"+reservation,"protocolVersion"))){
            ReservationIntent r=new ReservationIntent();r.setSkuId(sku);r.setUserId(Long.parseLong(user.toString()));r.setReservationId(reservation);
            Long result=redis.execute(vectorRelease,runtimeKeys(r,false),"",reservation,"","RELEASE");
            BusinessException.require(result!=null&&result>=0,"跨桶库存释放待恢复");return;
        }
        Object stored=redis.opsForHash().get(p+"reservation:"+reservation,"stockKey");
        String stockKey=stored==null ? p+"stock" : stored.toString();
        BusinessException.require(stockKey.equals(p+"stock") || stockKey.startsWith(p+"stock:bucket:"),"库存释放桶标识异常");
        Long result = redis.execute(release, Arrays.asList(stockKey, p + "user:" + user, p + "reservation:" + reservation, p + "pending"), reservation);
        BusinessException.require(result != null && result >= 0, "库存释放待恢复");
    }
    public long markSoldOut(ReservationIntent r,long version,String waitingDeadline){Long result=redis.execute(soldOut,runtimeKeys(r,false),r.getFingerprint(),r.getReservationId(),Long.toString(version),waitingDeadline);return result==null?-9:result;}
    public static Map<Integer,Integer> decodeAllocation(String raw,int quantity){
        try{List<?> values=new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw,List.class);BusinessException.require(values.size()==16,"跨桶分配长度异常");Map<Integer,Integer> result=new TreeMap<>();long sum=0;
            for(int i=0;i<16;i++){Object v=values.get(i);BusinessException.require(v instanceof Number&&((Number)v).doubleValue()==((Number)v).intValue()&&((Number)v).intValue()>=0,"跨桶分配数量异常");int n=((Number)v).intValue();if(n>0)result.put(i,n);sum+=n;}
            BusinessException.require(sum==quantity&&quantity>0,"跨桶分配总数异常");return Collections.unmodifiableMap(result);
        }catch(java.io.IOException error){throw new IllegalStateException("Invalid allocation marker",error);}
    }
}
