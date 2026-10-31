package fullchain;
import com.ticketing.TicketPlatformApplication;
import com.ticketing.ticket.*;
import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.MeterRegistry;
import javax.servlet.*;
import javax.servlet.http.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class FullChainProbe {
 public static final Set<String> TRANSFORMED=ConcurrentHashMap.newKeySet();
 public static final Map<String,Trace> ALL=new ConcurrentHashMap<>();
 public static final ThreadLocal<Trace> CURRENT=new ThreadLocal<>();
 public static final ThreadLocal<Deque<String>> PHASE=ThreadLocal.withInitial(ArrayDeque::new);
 public static final AtomicLong FORBIDDEN=new AtomicLong();
 public static final ThreadLocal<Deque<Trace>> CLAIMED_QUEUE=ThreadLocal.withInitial(ArrayDeque::new);
 public static final ThreadLocal<Trace> SUBMIT_TRACE=new ThreadLocal<>();
 public static final ThreadLocal<Long> CONNECTION=new ThreadLocal<>();
 public static final List<Map<String,Object>> SCHEDULED=Collections.synchronizedList(new ArrayList<>());
 public static final List<Map<String,Object>> CLAIMS=Collections.synchronizedList(new ArrayList<>());
 public static final List<Map<String,Object>> THREADS=Collections.synchronizedList(new ArrayList<>());
 public static final AtomicLong CONNECTION_MISSES=new AtomicLong();
 public static void connected(Object c){try{Class<?> type=Class.forName("com.mysql.jdbc.Connection");Object raw=((java.sql.Connection)c).unwrap(type);CONNECTION.set(((Number)raw.getClass().getMethod("getId").invoke(raw)).longValue());}catch(Exception e){CONNECTION_MISSES.incrementAndGet();}}
 public static void saleSubmit(String pool){if(!"sale".equals(pool))return;Trace t=CLAIMED_QUEUE.get().pollFirst();SUBMIT_TRACE.set(t);if(t!=null)t.saleSubmit=System.nanoTime();}
 public static void saleSubmitted(Throwable error){Trace t=SUBMIT_TRACE.get();if(t!=null){if(error==null)t.saleSubmitSuccess=true;else t.saleSubmitErrors++;}SUBMIT_TRACE.remove();}
 public static class Poll {Trace prior,timer;Deque<String> phases;long start;String thread;}
 public static Object pollStart(){Poll p=new Poll();p.prior=CURRENT.get();p.phases=new ArrayDeque<>(PHASE.get());p.timer=new Trace("dispatch");CURRENT.set(p.timer);PHASE.get().push("ReservationV2Service.dispatchOnce");p.start=System.nanoTime();p.thread=Thread.currentThread().getName();return p;}
 public static void pollEnd(Object value){Poll p=(Poll)value;Map<String,Object> e=new LinkedHashMap<>();e.put("operation","ReservationV2Service.dispatchOnce");e.put("start",p.start);e.put("end",System.nanoTime());e.put("thread",p.thread);e.put("spans",p.timer.spans);SCHEDULED.add(e);clear();if(p.prior!=null)CURRENT.set(p.prior);PHASE.set(p.phases);}
 public static <T> List<T> snapshot(List<T> values){synchronized(values){return new ArrayList<>(values);}}
 public static Map<String,Object> copyTrace(Trace t){Map<String,Object> result=new LinkedHashMap<>();try{for(java.lang.reflect.Field f:Trace.class.getFields()){Object v=f.get(t);result.put(f.getName(),v instanceof List?snapshot((List<?>)v):v);}}catch(IllegalAccessException e){throw new IllegalStateException(e);}return result;}
 public static void processEnd(){Trace t=CURRENT.get();if(t!=null)t.saleEnd=System.nanoTime();clear();}

 public static final ThreadLocal<Integer> SQL_DEPTH=ThreadLocal.withInitial(()->0);
 public static long sqlStart(Object[] args){if(CURRENT.get()==null||args.length==0||!(args[0] instanceof String))return 0;SQL_DEPTH.set(SQL_DEPTH.get()+1);return System.nanoTime();}
 public static void sqlEnd(Object[] args,long start,Throwable error,Object result){if(start==0)return;int depth=SQL_DEPTH.get();if(depth==1){Map<String,Object> m=span("sql "+args[0],start,error);if(m!=null){m.put("connectionId",CONNECTION.get());if(result instanceof Number)m.put("value",result);if(result==null)m.put("nullResult",true);if(result instanceof Map){Map<?,?> row=(Map<?,?>)result;m.put("resultState",row.get("state"));m.put("resultId",row.get("id"));}if(args.length>1&&args[1] instanceof Object[]){List<String> binds=new ArrayList<>();for(Object value:(Object[])args[1])binds.add(String.valueOf(value));m.put("binds",binds);}}}SQL_DEPTH.set(depth-1);}
 public static class Trace {
  public String requestId;public volatile long httpStart,enqueue,consumerStart,consumerEnd,claimStart,claimEnd,saleStart,redisHeld,orderInserted,locked,httpEnd;
  public volatile int status;public volatile long submitCount,workCount,saleSubmit,saleEnd;public volatile int claimCount,saleSubmitErrors;public volatile boolean saleSubmitSuccess,aborted;public String ingressThread,saleThread,decision,claimBatchId,claimLease,processLease;public final List<Map<String,Object>> httpAttempts=Collections.synchronizedList(new ArrayList<>());public final List<Map<String,Object>> spans=Collections.synchronizedList(new ArrayList<>());
  Trace(String id){requestId=id;}
 }
 public static Object field(Object obj,String name){return ReflectionTestUtils.getField(obj,name);}
 public static Trace trace(String id){return ALL.computeIfAbsent(id,Trace::new);}
 public static String request(Object work){Object entry=field(work,"entry");return ((ReserveRequest)field(entry,"request")).getRequestId();}
 public static void submit(ThreadPoolExecutor executor,Runnable work){Trace t=trace(request(work));t.enqueue=System.nanoTime();executor.execute(work);t.submitCount++;}
 public static void workStart(Object work){Trace t=trace(request(work));CURRENT.set(t);t.consumerStart=System.nanoTime();t.workCount++;t.ingressThread=Thread.currentThread().getName();}
 public static void workEnd(){Trace t=CURRENT.get();if(t!=null)t.consumerEnd=System.nanoTime();clear();}
 @SuppressWarnings("unchecked") public static void processStart(Object value){Map<String,Object> job=(Map<String,Object>)value;Trace t=trace(job.get("request_id").toString());CURRENT.set(t);t.processLease=String.valueOf(job.get("lease_token"));t.saleStart=System.nanoTime();t.saleThread=Thread.currentThread().getName();}
 public static void clear(){CURRENT.remove();PHASE.remove();}
 public static String phase(){return PHASE.get().isEmpty()?"":PHASE.get().peek();}
 public static void record(String method,long start,Throwable error){span(method,start,error);}
 public static Map<String,Object> span(String method,long start,Throwable error){Trace t=CURRENT.get();if(t==null)return null;Map<String,Object> m=new LinkedHashMap<>();m.put("operation",method);m.put("parent",phase());m.put("start",start);m.put("end",System.nanoTime());m.put("thread",Thread.currentThread().getName());m.put("connectionId",CONNECTION.get());if(error!=null)m.put("error",error.toString());t.spans.add(m);return m;}
 public static void main(String[] args){SpringApplication.run(new Class<?>[]{TicketPlatformApplication.class,Configuration.class},args);}
 @TestConfiguration @Import(Endpoint.class) public static class Configuration {
  @Bean Object guardedEnvironment(Environment env){if(!env.getRequiredProperty("spring.datasource.url").startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_")||!"26379".equals(env.getProperty("spring.redis.port"))||!"127.0.0.1".equals(env.getProperty("server.address")))throw new IllegalStateException("Explicit isolated lock diagnostic environment required");return new Object();}
  @Bean ProbeAspect lockProbeAspect(){return new ProbeAspect();}
  @Bean(destroyMethod="close") Monitor monitor(ReservationIngress ingress,HikariDataSource ds,com.ticketing.infrastructure.TaskPools pools){return new Monitor(ingress,ds,pools);}

  @Bean FilterRegistrationBean<OncePerRequestFilter> lockTimingFilter(){
   FilterRegistrationBean<OncePerRequestFilter> b=new FilterRegistrationBean<>();b.setOrder(Integer.MIN_VALUE);
   b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
    if(!"POST".equals(req.getMethod())||!"/api/tickets/reservations".equals(req.getRequestURI())){chain.doFilter(req,res);return;}
    String id=req.getHeader("X-Lock-Diag");if(id==null)throw new IllegalStateException("Diagnostic ID required");Trace t=trace(id);long attemptStart=System.nanoTime();boolean first=t.httpStart==0;if(first)t.httpStart=attemptStart;CURRENT.set(t);
    try{chain.doFilter(req,res);}finally{if(first){t.httpEnd=System.nanoTime();t.status=res.getStatus();}Map<String,Object> attempt=new LinkedHashMap<>();attempt.put("start",attemptStart);attempt.put("end",System.nanoTime());attempt.put("status",res.getStatus());t.httpAttempts.add(attempt);clear();}
   }});return b;
  }
 }
 @Aspect public static class ProbeAspect {
  @Around("execution(* com.ticketing.risk.RiskHistorySummary.refreshPending(..)) || execution(* com.ticketing.infrastructure.PlatformMetrics.refreshSnapshot(..)) || execution(* com.ticketing.ticket.ReservationIngress.reap(..)) || execution(* com.ticketing.config.HikariPoolMonitor.logPoolStats(..))")
  public Object scheduled(ProceedingJoinPoint p)throws Throwable{
   Trace previous=CURRENT.get();Trace timer=new Trace("scheduled");CURRENT.set(timer);long start=System.nanoTime();String method=p.getSignature().getDeclaringType().getSimpleName()+"."+p.getSignature().getName();
   try{return p.proceed();}finally{Map<String,Object> event=new LinkedHashMap<>();event.put("operation",method);event.put("start",start);event.put("end",System.nanoTime());event.put("thread",Thread.currentThread().getName());event.put("spans",timer.spans);SCHEDULED.add(event);clear();if(previous!=null)CURRENT.set(previous);}
  }

  @Around("execution(* com.ticketing.risk.RiskAgentClient.analyze*(..)) || execution(* com.ticketing.payment.WalletService.pay(..)) || execution(* com.ticketing.payment.AutoPaymentService.attempt(..)) || execution(* com.ticketing.mq.TicketEventConsumer.consume(..))")
  public Object forbidden(ProceedingJoinPoint p){FORBIDDEN.incrementAndGet();throw new IllegalStateException("Forbidden external/fulfillment path in lock-only diagnosis: "+p.getSignature());}
  @Around("execution(* com.ticketing.ticket.ReservationDispatch.create(..)) || execution(* com.ticketing.ticket.ReservationDispatch.decided(..)) || execution(* com.ticketing.ticket.ReservationV2Transactions.*(..)) || execution(* com.ticketing.risk.RiskDecisionService.classifyReservation(..)) || execution(* com.ticketing.risk.RiskFeatureService.collect(..)) || execution(* com.ticketing.risk.RiskRuleEngine.evaluate(..)) || execution(* com.ticketing.sale.TicketSaleArmory.runtime*(..)) || execution(* com.ticketing.risk.RiskSubjectFence.*(..)) || execution(* com.ticketing.inventory.InventoryService.reserveRuntime(..)) || execution(* com.ticketing.repository.jdbc.JdbcOrderRepository.insertWaitingPayment(..)) || execution(* org.springframework.data.redis.core.RedisTemplate.execute(org.springframework.data.redis.core.script.RedisScript,..))")
  public Object measure(ProceedingJoinPoint p)throws Throwable{
   Trace t=CURRENT.get();if(t==null)return p.proceed();String method=p.getSignature().getDeclaringType().getSimpleName()+"."+p.getSignature().getName();
   String label=method;if(p.getArgs().length>0&&p.getArgs()[0] instanceof String&&(method.startsWith("Db.")||method.startsWith("JdbcTemplate.")))label+=" "+p.getArgs()[0];
   if(method.equals("RedisTemplate.execute"))label="redis.script."+(phase().contains("InventoryService.reserveRuntime")?"reserve":"other");
   PHASE.get().push(method);long start=System.nanoTime();Throwable error=null;
   try{Object value=p.proceed();long end=System.nanoTime();if(method.equals("RiskDecisionService.classifyReservation"))t.decision=String.valueOf(((Map<?,?>)value).get("decision"));
    if(method.equals("InventoryService.reserveRuntime")&&value instanceof Number&&((Number)value).longValue()>=100)t.redisHeld=end;
    if(p.getSignature().getName().equals("insertWaitingPayment"))t.orderInserted=end;
    if(method.equals("ReservationV2Transactions.reserve")&&t.orderInserted>0)t.locked=end;
    if(method.equals("ReservationV2Transactions.abort"))t.aborted=true;
    return value;
   }catch(Throwable e){error=e;throw e;}finally{record(label,start,error);PHASE.get().pop();}
  }
  @Around("execution(* com.ticketing.ticket.ReservationDispatch.claim(..)) || execution(* com.ticketing.ticket.ReservationDispatch.claimBatch(..))")
  public Object claim(ProceedingJoinPoint p)throws Throwable{
   Trace previous=CURRENT.get();Deque<String> phases=new ArrayDeque<>(PHASE.get());Trace temporary=new Trace("claim");CURRENT.set(temporary);PHASE.get().push("ReservationDispatch."+p.getSignature().getName());long start=System.nanoTime();
   Map<String,Object> attempt=new LinkedHashMap<>();String id=UUID.randomUUID().toString();attempt.put("batchId",id);attempt.put("kind",p.getSignature().getName());attempt.put("start",start);attempt.put("thread",Thread.currentThread().getName());List<String> ids=new ArrayList<>();List<Object> leases=new ArrayList<>();CLAIMED_QUEUE.get().clear();
   try{Object value=p.proceed();long at=System.nanoTime();List<?> jobs=value instanceof List?(List<?>)value:value instanceof Map?Collections.singletonList(value):Collections.emptyList();
    for(Object item:jobs){Map<?,?> job=(Map<?,?>)item;Trace t=trace(job.get("request_id").toString());t.claimStart=start;t.claimEnd=at;t.claimBatchId=id;t.claimLease=String.valueOf(job.get("lease_token"));t.claimCount++;CLAIMED_QUEUE.get().addLast(t);ids.add(t.requestId);Map<String,Object> lease=new LinkedHashMap<>();lease.put("requestId",t.requestId);lease.put("jobId",job.get("id"));lease.put("token",t.claimLease);lease.put("state",job.get("state"));leases.add(lease);}
    return value;
   }catch(Throwable e){attempt.put("error",e.toString());throw e;}
   finally{attempt.put("end",System.nanoTime());attempt.put("requestIds",ids);attempt.put("leases",leases);attempt.put("size",ids.size());attempt.put("spans",snapshot(temporary.spans));CLAIMS.add(attempt);clear();if(previous!=null)CURRENT.set(previous);PHASE.set(phases);}
  }
 }
 @RestController public static class Endpoint {
  final ReservationIngress ingress;final HikariDataSource data;final MeterRegistry metrics;final Environment env;final com.ticketing.infrastructure.TaskPools pools;
  Endpoint(ReservationIngress i,HikariDataSource d,MeterRegistry m,Environment e,com.ticketing.infrastructure.TaskPools p){ingress=i;data=d;metrics=m;env=e;pools=p;}
  @GetMapping("/api/admin/lock-diag") public Map<String,Object> snapshot(@RequestParam(value="full",defaultValue="false") boolean full){
   Map<String,Object> m=new LinkedHashMap<>();List<Object> traces=new ArrayList<>();for(Trace t:ALL.values()){
    if(full){traces.add(copyTrace(t));continue;}Map<String,Object> v=new LinkedHashMap<>();v.put("requestId",t.requestId);v.put("enqueue",t.enqueue);v.put("consumerStart",t.consumerStart);v.put("consumerEnd",t.consumerEnd);v.put("decision",t.decision);v.put("redisHeld",t.redisHeld);v.put("orderInserted",t.orderInserted);v.put("locked",t.locked);v.put("saleEnd",t.saleEnd);v.put("claimCount",t.claimCount);v.put("aborted",t.aborted);traces.add(v);
   }m.put("traces",traces);m.put("transformed",TRANSFORMED);m.put("forbiddenCalls",FORBIDDEN.get());
   ThreadPoolExecutor pool=(ThreadPoolExecutor)field(ingress,"executor");m.put("ingressQueue",pool.getQueue().size());m.put("ingressActive",pool.getActiveCount());m.put("ingressCore",pool.getCorePoolSize());m.put("ingressCapacity",pool.getQueue().size()+pool.getQueue().remainingCapacity());
   com.zaxxer.hikari.HikariPoolMXBean hp=data.getHikariPoolMXBean();Map<String,Object> hikari=new LinkedHashMap<>();hikari.put("active",hp.getActiveConnections());hikari.put("idle",hp.getIdleConnections());hikari.put("waiting",hp.getThreadsAwaitingConnection());hikari.put("max",data.getMaximumPoolSize());m.put("hikari",hikari);
   Map<String,Object> gauges=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:metrics.getMeters())if(meter instanceof io.micrometer.core.instrument.Gauge&&(meter.getId().getName().startsWith("threadpool.")||meter.getId().getName().startsWith("reservation.ingress.")))gauges.put(meter.getId().getName()+meter.getId().getTags(),((io.micrometer.core.instrument.Gauge)meter).value());m.put("gauges",gauges);
   Map<String,Object> counters=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:metrics.getMeters())if(meter instanceof io.micrometer.core.instrument.Counter)counters.put(meter.getId().getName()+meter.getId().getTags(),((io.micrometer.core.instrument.Counter)meter).count());m.put("counters",counters);
   List<Object> gc=new ArrayList<>();for(GarbageCollectorMXBean g:ManagementFactory.getGarbageCollectorMXBeans())gc.add(Arrays.asList(g.getName(),g.getCollectionCount(),g.getCollectionTime()));m.put("gc",gc);@SuppressWarnings("unchecked") Map<String,org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor> pm=(Map<String,org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor>)field(pools,"pools");ThreadPoolExecutor se=pm.get("sale").getThreadPoolExecutor();m.put("saleActive",se.getActiveCount());m.put("saleQueue",se.getQueue().size());m.put("saleCore",se.getCorePoolSize());m.put("saleMax",se.getMaximumPoolSize());m.put("saleQueueCapacity",se.getQueue().size()+se.getQueue().remainingCapacity());
   Map<String,Object> flags=new LinkedHashMap<>();for(String key:Arrays.asList("isolated","batch-claim","wakeup"))flags.put(key,env.getProperty("ticketing.reservations-v2.dispatch."+key,Boolean.class,false));m.put("dispatchFlags",flags);
   m.put("postRate",env.getProperty("ticketing.ingress.post-rate",Integer.class,1000));m.put("persistentCapacity",env.getProperty("ticketing.reservations-v2.queue-capacity",Integer.class,512));m.put("globalActive",env.getProperty("ticketing.reservations-v2.workers",Integer.class,4));
   m.put("nano",System.nanoTime());m.put("wallMillis",System.currentTimeMillis());m.put("connectionIdMisses",CONNECTION_MISSES.get());if(full){m.put("scheduled",FullChainProbe.snapshot(SCHEDULED));m.put("claimAttempts",FullChainProbe.snapshot(CLAIMS));m.put("threadSamples",FullChainProbe.snapshot(THREADS));}return m;
  }
 }

 public static class Monitor implements AutoCloseable {
  final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fullchain-observer");t.setDaemon(true);return t;});
  Monitor(ReservationIngress ingress,HikariDataSource ds,com.ticketing.infrastructure.TaskPools pools){
   final ThreadMXBean bean=ManagementFactory.getThreadMXBean();
   sampler.scheduleWithFixedDelay(()->{try{
    if(THREADS.size()>=12000)return;Map<String,Object> row=new LinkedHashMap<>();row.put("nano",System.nanoTime());row.put("wallMillis",System.currentTimeMillis());List<Object> threads=new ArrayList<>();
    ThreadInfo[] all=bean.getThreadInfo(bean.getAllThreadIds(),10);
    for(ThreadInfo info:all){if(info==null)continue;String name=info.getThreadName();if(!(name.startsWith("sale-")||name.startsWith("scheduling-")||name.startsWith("lettuce-")))continue;
     Map<String,Object> item=new LinkedHashMap<>();item.put("name",name);item.put("id",info.getThreadId());item.put("state",info.getThreadState().toString());item.put("cpuNs",bean.isThreadCpuTimeSupported()?bean.getThreadCpuTime(info.getThreadId()):-1);item.put("lock",info.getLockName());item.put("owner",info.getLockOwnerName());List<String> stack=new ArrayList<>();for(StackTraceElement e:info.getStackTrace())stack.add(e.toString());item.put("stack",stack);threads.add(item);
    }row.put("threads",threads);
    ThreadPoolExecutor entry=(ThreadPoolExecutor)field(ingress,"executor");row.put("ingressActive",entry.getActiveCount());row.put("ingressQueue",entry.getQueue().size());
    @SuppressWarnings("unchecked") Map<String,org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor> map=(Map<String,org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor>)field(pools,"pools");ThreadPoolExecutor sale=map.get("sale").getThreadPoolExecutor();row.put("saleActive",sale.getActiveCount());row.put("saleQueue",sale.getQueue().size());row.put("saleCompleted",sale.getCompletedTaskCount());
    HikariPoolMXBeanWrapper(row,ds);THREADS.add(row);
   }catch(Throwable e){Map<String,Object> row=new LinkedHashMap<>();row.put("error",e.toString());THREADS.add(row);}},0,100,TimeUnit.MILLISECONDS);
  }
  static void HikariPoolMXBeanWrapper(Map<String,Object> row,HikariDataSource ds){com.zaxxer.hikari.HikariPoolMXBean h=ds.getHikariPoolMXBean();if(h!=null){row.put("hikariActive",h.getActiveConnections());row.put("hikariWaiting",h.getThreadsAwaitingConnection());}}
  public void close(){sampler.shutdownNow();}
 }
}
