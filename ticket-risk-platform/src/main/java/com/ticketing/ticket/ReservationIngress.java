package com.ticketing.ticket;

import com.ticketing.common.BusinessException;
import io.micrometer.core.instrument.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Volatile, bounded ingress. Receipt is NOT a durable acceptance or an inventory promise. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationIngress {
    private final ReservationV2Service backend;
    private final ReservationIntentService intents;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor executor;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<Long,Integer> perUser=new HashMap<>();
    private final Semaphore readers=new Semaphore(2);
    private final Bucket posts,queries;
    private final int stateLimit,userLimit;
    private final long waitMillis,retentionMillis;
    private volatile boolean closed;
    private static final String RECEIPT="已收到请求，正在尝试处理；不代表可靠受理或抢票成功。";

    public ReservationIngress(ReservationV2Service backend,ReservationIntentService intents,MeterRegistry metrics,Environment env){
        this.backend=backend;this.intents=intents;this.metrics=metrics;
        int workers=env.getProperty("ticketing.ingress.workers",Integer.class,4);
        int capacity=env.getProperty("ticketing.ingress.capacity",Integer.class,256);
        stateLimit=env.getProperty("ticketing.ingress.state-capacity",Integer.class,4096);
        userLimit=env.getProperty("ticketing.ingress.per-user-inflight",Integer.class,4);
        waitMillis=env.getProperty("ticketing.ingress.wait-ms",Long.class,120000L);
        retentionMillis=env.getProperty("ticketing.ingress.retention-ms",Long.class,60000L);
        if(workers<1||workers>32||capacity<1||capacity>65536||stateLimit<capacity+workers||stateLimit>100000
            ||userLimit<1||waitMillis<100||waitMillis>600000||retentionMillis<1000||retentionMillis>3600000)
            throw new IllegalArgumentException("Invalid bounded ingress settings");
        posts=new Bucket(env.getProperty("ticketing.ingress.post-rate",Integer.class,1000));
        queries=new Bucket(env.getProperty("ticketing.ingress.query-rate",Integer.class,200));
        AtomicLong ids=new AtomicLong();
        executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),
            task->{Thread thread=new Thread(task,"sale-ingress-"+ids.incrementAndGet());thread.setDaemon(false);return thread;},new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("reservation.ingress.queue.depth",executor,x->x.getQueue().size()).register(metrics);
        Gauge.builder("reservation.ingress.active",executor,ThreadPoolExecutor::getActiveCount).register(metrics);
        Gauge.builder("reservation.ingress.states",this,x->x.size()).register(metrics);
        Gauge.builder("reservation.ingress.oldest.wait",this,x->x.oldestWaitSeconds()).register(metrics);
    }
    public long waitMillis(){return waitMillis;}
    public Map<String,Object> receive(ReserveRequest request,String ip){
        if(!backend.isAccepting()||closed)throw reject("closed",503,"抢票请求接收已暂停");
        if(!posts.take())throw reject("rate_limited",429,"请求过于频繁，请使用相同请求编号稍后重试");
        if(backend.usesDurableAttempts())return backend.registerAttempt(copy(request),ip);
        ReserveRequest copy=copy(request);String fingerprint=intents.fingerprint(copy);
        synchronized(entries){
            Entry prior=entries.get(copy.getRequestId());
            if(prior!=null){
                match(prior,copy.getUserId(),fingerprint);
                if(!prior.finished.get()||"HANDED_OFF".equals(prior.stage.get()))return receipt(prior,false);
            }
            if(prior==null&&entries.size()>=stateLimit)throw reject("state_full",429,"请求跟踪容量已满，请保留原请求编号稍后重试");
            if(perUser.getOrDefault(copy.getUserId(),0)>=userLimit)throw reject("user_limited",429,"尚有请求处理中，请使用原请求编号查询");
            Entry entry=new Entry(copy,ip,fingerprint,System.currentTimeMillis());
            entries.put(copy.getRequestId(),entry);perUser.merge(copy.getUserId(),1,Integer::sum);
            try{executor.execute(entry.work);}
            catch(RejectedExecutionException full){entries.remove(copy.getRequestId(),entry);finish(entry);throw reject("queue_full",429,"处理入口已满，请使用相同请求编号稍后重试");}
            metrics.counter("reservation.ingress.submitted").increment();
            return receipt(entry,true);
        }
    }
    private Map<String,Object> receipt(Entry entry,boolean submitted){
        metrics.counter("reservation.ingress.received.response").increment();
        Map<String,Object> value=uncertain("RECEIVED",entry.request.getRequestId(),entry.created,false);
        value.put("received",true);value.put("newSubmission",submitted);value.put("message",RECEIPT);return value;
    }
    /** Any node can read durable outcomes. A cache miss is never authoritative failure. */
    public Map<String,Object> status(String request,long user,Long receivedAt){
        if(request==null||request.trim().isEmpty()||request.length()>80)throw new BusinessException(400,"请求编号不合法");
        Entry local; synchronized(entries){local=entries.get(request);}
        // A volatile ID collision on this node must not shadow the durable owner's result.
        // Other users never see this node's private temporary state; DB ownership remains authoritative.
        if(local!=null&&local.request.getUserId()!=user)local=null;
        long received=receivedAt!=null?receivedAt:local!=null?local.created:System.currentTimeMillis();
        if(received<0||received>System.currentTimeMillis()+60000)throw new BusinessException(400,"接收时间不合法");
        boolean expired=System.currentTimeMillis()-received>=waitMillis;
        if(!queries.take()||!readers.tryAcquire())return uncertain("STATUS_BUSY",request,received,expired);
        try{
            Map<String,Object> known=backend.lookupRequest(request,user);
            if(known!=null){
                Map<String,Object> value=new LinkedHashMap<>(known);
                String state=String.valueOf(value.get("state"));
                boolean terminal=!Arrays.asList("EVALUATING","QUEUED","CLAIMED","WAITING","PROCESSING").contains(state);
                value.put("authoritative",true);value.put("terminal",terminal);value.put("reliableAccepted",true);
                if(value.containsKey("attemptId")){value.put("waitExpired",false);value.put("pollingSuggested",!terminal);}
                else{value.put("waitExpired",expired);value.put("pollingSuggested",!terminal&&!expired);}return value;
            }
            // V3: no MySQL row yet. Redis may know the request is being processed; that is never a failure.
            Map<String,Object> pending=backend.pendingStatus(request,user);
            if(pending!=null&&!pending.isEmpty()){
                if(pending.containsKey("orderId")||Boolean.TRUE.equals(pending.get("authoritative")))return pending;
                Map<String,Object> value=uncertain(expired?"UNCONFIRMED":"PROCESSING",request,received,expired);
                value.put("received",true);value.putAll(pending);value.put("message",pending.containsKey("message")?pending.get("message"):expired?value.get("message"):V3Entry.PROCESSING_MESSAGE);
                return value;
            }
        }catch(BusinessException denied){if(denied.status==403||denied.status==400)throw denied;return uncertain("STATUS_UNAVAILABLE",request,received,expired);}
        catch(RuntimeException unavailable){metrics.counter("reservation.ingress.status.unavailable").increment();return uncertain("STATUS_UNAVAILABLE",request,received,expired);}
        finally{readers.release();}
        String stage=local==null?"UNKNOWN":local.stage.get();
        boolean attemptEnded=Arrays.asList("ATTEMPT_REJECTED","UNCERTAIN","EXPIRED").contains(stage);
        Map<String,Object> value=uncertain(expired?"UNCONFIRMED":stage,request,received,expired);
        if(attemptEnded)value.put("pollingSuggested",false);
        return value;
    }
    private Map<String,Object> uncertain(String state,String request,long received,boolean expired){
        Map<String,Object> value=new LinkedHashMap<>();value.put("requestId",request);value.put("state",state);
        value.put("receivedAt",received);value.put("waitUntil",received+waitMillis);value.put("waitExpired",expired);
        value.put("reservationSuccess",false);value.put("reliableAccepted",false);value.put("authoritative",false);
        value.put("terminal",false);value.put("pollingSuggested",!expired);value.put("retryAfterMs",1000);
        value.put("retryWithSameRequestId",true);value.put("message",expired?"尚未确认最终结果，请保留原请求编号重试或查看订单。":RECEIPT);return value;
    }
    private BusinessException reject(String reason,int status,String message){metrics.counter("reservation.ingress.rejected","reason",reason).increment();return backend.usesDurableAttempts()?new AttemptAdmissionException(status,"rate_limited".equals(reason)?"RATE_LIMITED":"SYSTEM_BUSY",message):new BusinessException(status,message);}
    private void match(Entry entry,long user,String fingerprint){
        if(entry.request.getUserId()!=user)throw new BusinessException(403,"请求编号已属于其他用户");
        if(!Objects.equals(entry.fingerprint,fingerprint))throw new BusinessException(409,"请求编号已用于其他购票参数");
    }
    private static ReserveRequest copy(ReserveRequest r){
        ReserveRequest x=new ReserveRequest();x.setRequestId(r.getRequestId());x.setUserId(r.getUserId());x.setEventSessionId(r.getEventSessionId());
        x.setTicketSkuId(r.getTicketSkuId());x.setQuantity(r.getQuantity());x.setDeviceId(r.getDeviceId());return x;
    }
    private void finish(Entry entry){
        synchronized(entries){
            if(entry.finished.get())return;
            entry.finishedAt=System.currentTimeMillis();entry.finished.set(true);
            long user=entry.request.getUserId();int n=perUser.getOrDefault(user,1)-1;if(n<=0)perUser.remove(user);else perUser.put(user,n);
        }
    }
    @Scheduled(fixedDelay=1000) public void reap(){
        long now=System.currentTimeMillis();synchronized(entries){
            Iterator<Entry> iterator=entries.values().iterator();while(iterator.hasNext()){
                Entry e=iterator.next();
                if(now-e.created>=waitMillis&&e.stage.compareAndSet("RECEIVED","EXPIRED")){
                    metrics.counter("reservation.ingress.expired").increment();if(executor.remove(e.work))finish(e);
                }
                if(e.finished.get()&&now-e.finishedAt>=retentionMillis)iterator.remove();
            }
        }
    }
    private int size(){synchronized(entries){return entries.size();}}
    private double oldestWaitSeconds(){long oldest=System.currentTimeMillis();synchronized(entries){for(Entry e:entries.values())if("RECEIVED".equals(e.stage.get()))oldest=Math.min(oldest,e.created);}return Math.max(0,(System.currentTimeMillis()-oldest)/1000d);}
    @PreDestroy public void close(){
        closed=true;for(Runnable task:executor.shutdownNow()){
            Work work=(Work)task;work.entry.stage.set("UNCERTAIN");finish(work.entry);metrics.counter("reservation.ingress.shutdown.discarded").increment();
        }
    }
    private final class Work implements Runnable {
        final Entry entry;Work(Entry entry){this.entry=entry;}
        public void run(){
            try{
                if(System.currentTimeMillis()-entry.created>=waitMillis){if(entry.stage.compareAndSet("RECEIVED","EXPIRED"))metrics.counter("reservation.ingress.expired").increment();return;}
                if(!entry.stage.compareAndSet("RECEIVED","PROCESSING"))return;
                backend.submit(entry.request,entry.ip);entry.stage.set("HANDED_OFF");metrics.counter("reservation.ingress.background.completed").increment();
            }catch(BusinessException rejected){entry.stage.set("ATTEMPT_REJECTED");metrics.counter("reservation.ingress.background.rejected").increment();}
            catch(RuntimeException failed){entry.stage.set("UNCERTAIN");metrics.counter("reservation.ingress.background.unavailable").increment();org.slf4j.LoggerFactory.getLogger(ReservationIngress.class).warn("Ingress attempt unresolved request={}",entry.request.getRequestId(),failed);}
            finally{finish(entry);}
        }
    }
    private final class Entry {
        final ReserveRequest request;final String ip,fingerprint;final long created;final Work work;
        final AtomicReference<String> stage=new AtomicReference<>("RECEIVED");final AtomicBoolean finished=new AtomicBoolean();volatile long finishedAt;
        Entry(ReserveRequest r,String ip,String fingerprint,long created){this.request=r;this.ip=ip;this.fingerprint=fingerprint;this.created=created;work=new Work(this);}
    }
    private static final class Bucket {
        final int rate;double tokens;long last=System.nanoTime();
        Bucket(int rate){if(rate<1||rate>100000)throw new IllegalArgumentException("Ingress rate must be bounded");this.rate=rate;tokens=rate;}
        synchronized boolean take(){long now=System.nanoTime();tokens=Math.min(rate,tokens+(now-last)*rate/1_000_000_000d);last=now;if(tokens<1)return false;tokens--;return true;}
    }
}
