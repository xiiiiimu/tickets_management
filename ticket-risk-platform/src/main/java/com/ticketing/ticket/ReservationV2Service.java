package com.ticketing.ticket;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.sale.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationV2Service {
    private final ReservationDispatch queue;private final TicketSaleArmory armory;private final RiskDecisionService risk;
    private final ReservationIntentService intents;private final ReservationV2Transactions transactions;private final TaskPools pools;private final MeterRegistry metrics;
    private final Map<String,io.micrometer.core.instrument.Timer> queueTimers=new HashMap<>();
    @Value("${ticketing.reservations-v2.accepting:true}") private boolean accepting;
    @Value("${ticketing.reservations-v2.dispatch.batch-claim:false}") private boolean batchClaim;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationDispatchScheduler scheduler;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationAttemptStore attempts;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptWorkerReadiness attemptReadiness;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.ticketing.risk.AttemptRiskAdmission quickAdmission;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationSoldOutSweep soldOutSweep;
    @Value("${ticketing.reservations-v2.attempts.quick-review:false}") private boolean requireQuickAdmission;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptAdmissionGate admissionGate;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AttemptRegistrar registrar;
    /** V3 (protocol 4): Redis acceptance + Stream dispatch. When present, protocol-3 registration and claims never run. */
    @org.springframework.beans.factory.annotation.Autowired(required=false) private V3Entry v3;
    /** Local worker slots; the persisted tb_reservation_dispatch.active_limit stays the global bound. */
    @Value("${ticketing.reservations-v2.attempts.workers:4}") private int maxAttemptWorkers=4;
    @Value("${ticketing.reservations-v2.attempts.batch-claim:false}") private boolean attemptBatchClaim;
    private final AtomicInteger attemptWorkers=new AtomicInteger();
    private final AtomicLong nextHeartbeat=new AtomicLong();
    private final ConcurrentMap<String,Map<String,Object>> runningAttempts=new ConcurrentHashMap<>();
    public ReservationV2Service(ReservationDispatch queue,TicketSaleArmory armory,RiskDecisionService risk,ReservationIntentService intents,
            ReservationV2Transactions transactions,TaskPools pools,MeterRegistry metrics){
        this.queue=queue;this.armory=armory;this.risk=risk;this.intents=intents;this.transactions=transactions;this.pools=pools;this.metrics=metrics;
        for(String decision:Arrays.asList("PASS","REVIEW"))queueTimers.put(decision,
            io.micrometer.core.instrument.Timer.builder("reservation.v2.queue.wait").tag("decision",decision)
            .serviceLevelObjectives(java.time.Duration.ofMillis(1),java.time.Duration.ofMillis(10),java.time.Duration.ofMillis(100),
                java.time.Duration.ofMillis(500),java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(5),java.time.Duration.ofSeconds(10),java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(60),java.time.Duration.ofSeconds(120)).register(metrics));
    }
    public Map<String,Object> submit(ReserveRequest r,String ip){
        if(attempts!=null)return registerAttempt(r,ip);
        long begin=System.nanoTime();
        try{
            String fingerprint=intents.fingerprint(r);Map<String,Object> previous=queue.byRequest(r.getRequestId());
            if(previous!=null){queue.match(previous,r,fingerprint);return queue.view(previous,r.getUserId());}
            if(!accepting)throw new BusinessException(503,"新抢票受理已暂停");
            SaleManifest plan=armory.runtimePlan(r.getEventSessionId(),r.getTicketSkuId());
            Map<String,Object> job=queue.create(r,ip,fingerprint,plan.version());
            if(Boolean.TRUE.equals(job.get("newlyCreated")))evaluate(job,plan);
            return queue.view(queue.get(job.get("id").toString()),r.getUserId());
        }finally{metrics.timer("reservation.v2.entry").record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
    public Map<String,Object> status(String reservation,long user){return attemptView(queue.byReservation(reservation),user);}
    /** V3 only: non-authoritative stage of a request that has no MySQL row yet (null = unknown, never a failure). */
    public Map<String,Object> pendingStatus(String request,long user){return v3==null?null:v3.pendingStatus(request,user);}
    public String receiptSemantics(){return v3!=null?V3Entry.RECEIPT_SEMANTICS:attempts!=null?"DURABLE_ATTEMPT":"VOLATILE_ASYNC_RECEIPT";}
    public Map<String,Object> lookupRequest(String request,long user){Map<String,Object> job=queue.byRequest(request);return job==null?null:attemptView(job,user);}
    public Map<String,Object> attemptStatus(String id,long user){return attemptView(queue.get(id),user);}
    private Map<String,Object> attemptView(Map<String,Object> job,long user){
        if(job!=null&&Db.number(job,"user_id")!=user)throw new BusinessException(403,"无权查看该attempt");
        if(attempts!=null&&ReservationAttemptStore.isAttempt(job)&&"WAITING".equals(job.get("state")))job=attempts.refresh(text(job,"id"));
        return queue.view(job,user);
    }
    public boolean usesDurableAttempts(){return attempts!=null;}
    /** HTTP receipt is built only after register's REQUIRES_NEW transaction has committed. */
    public Map<String,Object> registerAttempt(ReserveRequest r,String ip){
        if(attempts==null)throw new IllegalStateException("Durable attempt mode is disabled");
        if(v3!=null){
            if(!accepting)throw new AttemptAdmissionException(503,"SYSTEM_BUSY","抢票接纳已暂停");
            return v3.accept(r,ip);
        }
        // Gate settlement: this method settles every outcome it determines itself; once the request is handed to the
        // registrar, the registrar settles it (also after this caller stopped waiting). Unknown outcomes stay unsettled.
        AttemptRegistrar.Settlement hold=AttemptRegistrar.Settlement.NONE;boolean handedOff=false;
        try{
            String fingerprint=intents.fingerprint(r);
            // Advisory early refusal before ANY MySQL work; known requestIds and every Redis uncertainty pass through.
            if(admissionGate!=null){AttemptAdmissionGate.Hold admitted=admissionGate.admit(r.getRequestId());if(admitted==null||admitted.refused())throw attempts.gateRejected();hold=admitted;}
            Map<String,Object> previous=queue.byRequest(r.getRequestId());
            if(previous!=null){hold.committed(false);queue.match(previous,r,fingerprint);if(ReservationAttemptStore.isAttempt(previous)&&"WAITING".equals(previous.get("state")))previous=attempts.refresh(text(previous,"id"));Map<String,Object> receipt=attemptReceipt(previous,r.getUserId(),false);metrics.counter("attempt.receipt.committed").increment();return receipt;}
            if(!accepting)throw new AttemptAdmissionException(503,"SYSTEM_BUSY","抢票接纳已暂停");
            if(attemptReadiness!=null&&!attemptReadiness.isReady())throw new AttemptAdmissionException(503,"SYSTEM_BUSY","服务尚未就绪，请使用原请求编号稍后重试");
            if(!attempts.dependenciesAvailable())throw attempts.dbBusy();
            SaleManifest plan=armory.runtimePlan(r.getEventSessionId(),r.getTicketSkuId());
            Map<String,Object> job;
            if(registrar!=null){handedOff=true;job=registrar.register(r,ip,fingerprint,plan.version(),hold);}
            else{job=attempts.register(r,ip,fingerprint,plan.version());hold.committed(Boolean.TRUE.equals(job.get("newlyCreated")));}
            Map<String,Object> result=attemptReceipt(job,r.getUserId(),Boolean.TRUE.equals(job.get("newlyCreated")));
            if(Boolean.TRUE.equals(job.get("newlyCreated")))metrics.counter("attempt.registered").increment();
            metrics.counter("attempt.receipt.committed").increment();return result;
        }catch(com.ticketing.common.BusinessException refused){
            // Refusals before the registrar, and refusals of the single REQUIRES_NEW register (rolled back): nothing written.
            if(!handedOff)hold.notRegistered();
            if(refused instanceof AttemptAdmissionException)metrics.counter("attempt.admission.rejected","reason",((AttemptAdmissionException)refused).code.toLowerCase(Locale.ROOT)).increment();
            throw refused;
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){
            // Outcome unknown: do not settle; the gate's held entry expires on its own.
            metrics.counter("attempt.admission.rejected","reason","dependency_unavailable").increment();
            throw new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","暂时无法确认登记结果，请使用原请求编号查询或重试");
        }
    }
    private Map<String,Object> attemptReceipt(Map<String,Object> job,long user,boolean created){
        // Return the committed registration snapshot; do not reacquire the job lock and wait for
        // a worker's stock transaction just to construct the first HTTP receipt.
        Map<String,Object> value=queue.view(job,user);value.put("received",true);value.put("newSubmission",created);
        value.put("attemptId",job.get("id"));value.put("reliableAccepted",true);value.put("authoritative",true);
        value.put("message","已可靠登记本次抢票尝试；不代表库存预占成功。");return value;
    }
    public boolean isAccepting(){return accepting;}
    @javax.annotation.PostConstruct public void startDispatchControl(){
        if(maxAttemptWorkers<1||maxAttemptWorkers>256)throw new IllegalArgumentException("attempts.workers must be 1..256");
        if(attempts!=null)attempts.localWorkers(attemptWorkers::get);
        if(requireQuickAdmission&&quickAdmission==null)throw new IllegalStateException("Quick admission requires enabled durable attempts and ordered Lua");
        if(scheduler!=null)scheduler.start(this::dispatchOnce);
    }
    private void evaluate(Map<String,Object> job,SaleManifest available){
        long begin=System.nanoTime();
        try{
            if(((java.sql.Timestamp)job.get("queue_expires_at")).getTime()<=System.currentTimeMillis()){queue.evaluationFailed(job,"QUEUE_EXPIRED");return;}
            SaleManifest plan=available==null?armory.runtimePlan(text(job,"session_id"),text(job,"sku_id")):available;
            if(plan.version()!=Db.number(job,"sale_version")){queue.evaluationFailed(job,"VERSION_CHANGED");return;}
            Map<String,Object> decision=risk.classifyReservation(Db.number(job,"user_id"),text(job,"device_id"),text(job,"ip"),plan.rules(),text(job,"request_id"));
            decision.put("saleVersion",plan.version());
            @SuppressWarnings("unchecked") Map<String,Object> policy=new LinkedHashMap<>((Map<String,Object>)decision.get("policy"));
            policy.put("saleAssemblyVersion",plan.version());policy.put("saleSessionId",plan.sessionId());decision.put("policy",policy);
            queue.decided(job,decision);
        }catch(RuntimeException unavailable){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("V2 admission execution failed job={}",job.get("id"),unavailable);
            // No BLOCK fabrication and no default PASS. If DB is down, retain EVALUATING + lease for recovery.
            try{queue.evaluationFailed(job,"RISK_UNAVAILABLE");}catch(RuntimeException dbDown){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Durable risk evaluation awaits recovery job={}",job.get("id"),dbDown);
            }
        }finally{metrics.timer("reservation.v2.risk").record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
    @Scheduled(fixedDelayString="${ticketing.reservations-v2.poll-ms:25}")
    public void dispatch(){
        if(scheduler!=null&&scheduler.isIsolated())return;
        dispatchOnce();
    }
    void dispatchOnce(){
        if(attempts!=null){dispatchAttempts();return;}
        if(batchClaim){dispatchBatch();return;}
        for(int i=0;i<4;i++){
            Map<String,Object> job;
            try{job=queue.claim();}catch(RuntimeException dbDown){return;}
            if(job==null)return;
            try{pools.execute("sale",()->process(job));}
            catch(RejectedExecutionException full){queue.yieldClaim(job);return;}
        }
    }
    private void dispatchBatch(){
        List<Map<String,Object>> jobs;
        try{jobs=queue.claimBatch(4);}catch(RuntimeException dbDown){return;}
        int submitted=0;
        try{
            for(Map<String,Object> job:jobs){pools.execute("sale",()->process(job));submitted++;}
        }catch(RejectedExecutionException full){
            // Successfully submitted jobs keep ownership; every unsent lease is yielded below.
        }finally{
            for(int i=submitted;i<jobs.size();i++){
                try{queue.yieldClaim(jobs.get(i));}
                catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unsubmitted batch lease awaits expiry job={}",jobs.get(i).get("id"),unavailable);}
            }
        }
    }
    void process(Map<String,Object> job){
        if(ReservationAttemptStore.isAttempt(job)){processAttempt(job);return;}
        if("EVALUATING".equals(job.get("state"))){evaluate(job,null);return;}
        java.sql.Timestamp queued=(java.sql.Timestamp)job.get("queued_at"),claimed=(java.sql.Timestamp)job.get("claimed_at");
        if(queued!=null&&claimed!=null)queueTimers.get(text(job,"decision")).record(Math.max(0,claimed.getTime()-queued.getTime()),TimeUnit.MILLISECONDS);
        else metrics.timer("reservation.v2.queue.wait.legacy","decision",text(job,"decision")).record(
                Math.max(0,System.currentTimeMillis()-((java.sql.Timestamp)job.get("created_at")).getTime()),TimeUnit.MILLISECONDS);
        try{transactions.prepare(job);transactions.reserve(job);}
        catch(ReservationV2Transactions.Rejected rejected){abort(job,rejected.code);}
        catch(BusinessException changed){abort(job,"ARMORY_OR_CONTEXT_CHANGED");}
        catch(RuntimeException unknown){
            // abort locks the job. A committed RESERVED row wins; an unavailable DB leaves the lease durable.
            abort(job,"EXECUTION_UNAVAILABLE");
        }
    }
    private void dispatchAttempts(){
        if(v3!=null)return; // V3StreamWorker owns dispatch, leases and expiry of protocol-4 work
        if(attemptReadiness!=null&&!attemptReadiness.ensureReady())return;
        if(soldOutSweep!=null)soldOutSweep.run();
        long now=System.nanoTime(),due=nextHeartbeat.get();
        if(now>=due&&nextHeartbeat.compareAndSet(due,now+TimeUnit.SECONDS.toNanos(5))){
            for(Map<String,Object> job:runningAttempts.values())try{attempts.heartbeat(job);}
            catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attempt lease renewal deferred id={}",job.get("id"),unavailable);}
        }
        int supplied;
        try{attempts.expireWaiting();if(attemptWorkers.get()>=maxAttemptWorkers)return;supplied=maxAttemptWorkers==4?attempts.availableHint():attempts.availableHint(maxAttemptWorkers);}catch(RuntimeException unavailable){return;}
        if(attemptBatchClaim){dispatchAttemptBatch(supplied);return;}
        for(int i=0;i<supplied;i++){
            int active=attemptWorkers.get();if(active>=maxAttemptWorkers||!attemptWorkers.compareAndSet(active,active+1))return;
            try{pools.execute("sale",()->{
                try{Map<String,Object> job=attempts.claim();if(job!=null)process(job);}
                catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attempt worker deferred; durable state retained",unavailable);}
                finally{attemptWorkers.decrementAndGet();}
            });}catch(RejectedExecutionException full){
                attemptWorkers.decrementAndGet();metrics.counter("attempt.worker.rejected").increment();return;
            }
        }
    }
    /**
     * Batch variant: reserve local slots first, grant up to that many attempts in ONE claim transaction on this
     * control thread, then hand each granted job to a worker. A grant the pool refuses is deferred at once
     * (lease released, PROCESSING kept) so another worker/node recovers it without waiting for lease expiry.
     */
    private void dispatchAttemptBatch(int supplied){
        int reserved=0;
        while(reserved<supplied){
            int active=attemptWorkers.get();if(active>=maxAttemptWorkers)break;
            if(attemptWorkers.compareAndSet(active,active+1))reserved++;
        }
        if(reserved==0)return;
        List<Map<String,Object>> granted;
        try{granted=attempts.claimBatch(reserved);}
        catch(RuntimeException unavailable){attemptWorkers.addAndGet(-reserved);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attempt batch claim deferred; durable state retained",unavailable);return;}
        attemptWorkers.addAndGet(-(reserved-granted.size()));
        for(int i=0;i<granted.size();i++){
            Map<String,Object> job=granted.get(i);
            try{pools.execute("sale",()->{
                try{process(job);}
                catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attempt worker deferred; durable state retained",unavailable);}
                finally{attemptWorkers.decrementAndGet();}
            });}catch(RejectedExecutionException full){
                for(int j=i;j<granted.size();j++){
                    attemptWorkers.decrementAndGet();metrics.counter("attempt.worker.rejected").increment();
                    try{attempts.defer(granted.get(j));}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unstarted grant awaits lease expiry id={}",granted.get(j).get("id"),unavailable);}
                }
                return;
            }
        }
    }
    /** V3 entry into the unchanged durable attempt pipeline (risk, prepare, ordered inventory Lua, HELD, abort). */
    void processV3(Map<String,Object> job){processV3(job,false);}
    /**
     * @param fresh the row was read inside the transaction that just granted our lease token (V3 start / takeover /
     *              orphan takeover). Sprint S2: such a row needs neither the current_owned re-read nor the in-flight
     *              lease renewal — {@code decided} and every later transaction re-check ownership with SELECT ... FOR
     *              UPDATE + lease token (lockOwned), and a lost lease still ends the work quietly there.
     */
    void processV3(Map<String,Object> job,boolean fresh){
        if(!ReservationAttemptStore.isV3(job))throw new IllegalStateException("Not a protocol-4 job");
        processAttempt(job,fresh&&skipFreshChecks);
    }
    private final boolean skipFreshChecks=Boolean.parseBoolean(System.getProperty("ticketing.v3.skip-fresh-checks",
            String.valueOf(env("TICKETING_V3_SKIP_FRESH_CHECKS","true"))));
    private static String env(String k,String d){String v=System.getenv(k);return v==null?d:v;}
    private void processAttempt(Map<String,Object> original){processAttempt(original,false);}
    private void processAttempt(Map<String,Object> original,boolean fresh){
        long start=System.nanoTime();Map<String,Object> job=original;String id=text(job,"id");runningAttempts.put(id,original);
        try{
            // attempt.path timers are observation only.
            if(!fresh){job=AttemptPath.time(metrics,"current_owned",()->attempts.currentOwned(original));if(job==null)return;}
            if(job.get("admission")==null){
                final Map<String,Object> owned=job;
                final boolean v3job=ReservationAttemptStore.isV3(owned);
                SaleManifest plan=AttemptPath.time(metrics,"plan_lookup",()->v3job?armory.cachedRuntimePlan(text(owned,"session_id"),text(owned,"sku_id")):armory.runtimePlan(text(owned,"session_id"),text(owned,"sku_id")));
                if(plan.version()!=Db.number(job,"sale_version"))throw new BusinessException(409,"装配版本已变更");
                Map<String,Object> decision=AttemptPath.time(metrics,"risk_classify",()->quickAdmission==null
                    ?risk.classifyReservation(Db.number(owned,"user_id"),text(owned,"device_id"),text(owned,"ip"),plan.rules(),text(owned,"request_id"))
                    :quickAdmission.classify(owned,plan));
                decision.put("saleVersion",plan.version());
                @SuppressWarnings("unchecked") Map<String,Object> policy=new LinkedHashMap<>((Map<String,Object>)decision.get("policy"));
                policy.put("saleAssemblyVersion",plan.version());policy.put("saleSessionId",plan.sessionId());decision.put("policy",policy);
                job=AttemptPath.time(metrics,"decided",()->attempts.decided(owned,decision));if(job==null)return;
            }
            // No queue deadline is consulted after the valid WAITING -> PROCESSING grant.
            final Map<String,Object> decided=job;
            if(!fresh&&!AttemptPath.time(metrics,"heartbeat",()->attempts.heartbeat(decided)))return;
            AttemptPath.run(metrics,"prepare",()->transactions.prepare(decided));
            AttemptPath.run(metrics,"reserve_and_held",()->transactions.reserve(decided));
        }catch(com.ticketing.risk.AttemptRiskAdmission.Unavailable unavailable){
            abort(original,unavailable.code);
        }catch(ReservationQualificationOrder.Pending pending){
            // No thread waits for another task and no qualification is re-run on the retry.
            attempts.defer(original);metrics.counter("attempt.ordered.deferred").increment();
        }catch(ReservationV2Transactions.Rejected rejected){abort(original,rejected.code);if("SOLD_OUT".equals(rejected.code)&&soldOutSweep!=null)soldOutSweep.signal(original);}
        catch(BusinessException changed){abort(original,"ARMORY_OR_CONTEXT_CHANGED");}
        catch(RuntimeException unknown){abort(original,"EXECUTION_UNAVAILABLE");}
        finally{
            runningAttempts.remove(id,original);long elapsed=System.nanoTime()-start;
            attempts.completed(elapsed);metrics.timer("attempt.processing.worker").record(elapsed,TimeUnit.NANOSECONDS);
        }
    }
    private void abort(Map<String,Object> job,String code){
        try{transactions.abort(job,code);metrics.counter("reservation.v2.failed","code",code).increment();}
        catch(ReservationQualificationOrder.Pending pending){if(attempts!=null)attempts.defer(job);}
        catch(RuntimeException pending){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reservation recovery retained job={}",job.get("id"),pending);}
    }
    private static String text(Map<String,Object> job,String key){return ReservationV2Transactions.text(job,key);}
}
