package lockdiag;
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

public class LockProbe {
 public static final Set<String> TRANSFORMED=ConcurrentHashMap.newKeySet();
 public static final Map<String,Trace> ALL=new ConcurrentHashMap<>();
 public static final ThreadLocal<Trace> CURRENT=new ThreadLocal<>();
 public static final ThreadLocal<Deque<String>> PHASE=ThreadLocal.withInitial(ArrayDeque::new);
 public static final AtomicLong FORBIDDEN=new AtomicLong();
 public static final ThreadLocal<Integer> SQL_DEPTH=ThreadLocal.withInitial(()->0);
 public static long sqlStart(Object[] args){if(CURRENT.get()==null||args.length==0||!(args[0] instanceof String))return 0;SQL_DEPTH.set(SQL_DEPTH.get()+1);return System.nanoTime();}
 public static void sqlEnd(Object[] args,long start,Throwable error){if(start==0)return;int depth=SQL_DEPTH.get();if(depth==1)record("sql "+args[0],start,error);SQL_DEPTH.set(depth-1);}
 public static class Trace {
  public String requestId;public volatile long httpStart,enqueue,consumerStart,consumerEnd,claimStart,claimEnd,saleStart,redisHeld,orderInserted,locked,httpEnd;
  public volatile int status;public volatile long submitCount,workCount;public String ingressThread,saleThread,decision;public final List<Map<String,Object>> spans=Collections.synchronizedList(new ArrayList<>());
  Trace(String id){requestId=id;}
 }
 public static Object field(Object obj,String name){return ReflectionTestUtils.getField(obj,name);}
 public static Trace trace(String id){return ALL.computeIfAbsent(id,Trace::new);}
 public static String request(Object work){Object entry=field(work,"entry");return ((ReserveRequest)field(entry,"request")).getRequestId();}
 public static void submit(ThreadPoolExecutor executor,Runnable work){Trace t=trace(request(work));t.enqueue=System.nanoTime();executor.execute(work);t.submitCount++;}
 public static void workStart(Object work){Trace t=trace(request(work));CURRENT.set(t);t.consumerStart=System.nanoTime();t.workCount++;t.ingressThread=Thread.currentThread().getName();}
 public static void workEnd(){Trace t=CURRENT.get();if(t!=null)t.consumerEnd=System.nanoTime();clear();}
 @SuppressWarnings("unchecked") public static void processStart(Object value){Map<String,Object> job=(Map<String,Object>)value;Trace t=trace(job.get("request_id").toString());CURRENT.set(t);t.saleStart=System.nanoTime();t.saleThread=Thread.currentThread().getName();}
 public static void clear(){CURRENT.remove();PHASE.remove();}
 public static String phase(){return PHASE.get().isEmpty()?"":PHASE.get().peek();}
 public static void record(String method,long start,Throwable error){Trace t=CURRENT.get();if(t==null)return;Map<String,Object> m=new LinkedHashMap<>();m.put("operation",method);m.put("parent",phase());m.put("start",start);m.put("end",System.nanoTime());m.put("thread",Thread.currentThread().getName());if(error!=null)m.put("error",error.toString());t.spans.add(m);}
 public static void main(String[] args){SpringApplication.run(new Class<?>[]{TicketPlatformApplication.class,Configuration.class},args);}
 @TestConfiguration @Import(Endpoint.class) public static class Configuration {
  @Bean Object guardedEnvironment(Environment env){if(!env.getRequiredProperty("spring.datasource.url").startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_lockdiag_")||!"26379".equals(env.getProperty("spring.redis.port"))||!"127.0.0.1".equals(env.getProperty("server.address")))throw new IllegalStateException("Explicit isolated lock diagnostic environment required");return new Object();}
  @Bean ProbeAspect lockProbeAspect(){return new ProbeAspect();}
  @Bean FilterRegistrationBean<OncePerRequestFilter> lockTimingFilter(){
   FilterRegistrationBean<OncePerRequestFilter> b=new FilterRegistrationBean<>();b.setOrder(Integer.MIN_VALUE);
   b.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
    if(!"POST".equals(req.getMethod())||!"/api/tickets/reservations".equals(req.getRequestURI())){chain.doFilter(req,res);return;}
    String id=req.getHeader("X-Lock-Diag");if(id==null)throw new IllegalStateException("Diagnostic ID required");Trace t=trace(id);boolean first=t.httpStart==0;if(first)t.httpStart=System.nanoTime();CURRENT.set(t);
    try{chain.doFilter(req,res);}finally{if(first){t.httpEnd=System.nanoTime();t.status=res.getStatus();}clear();}
   }});return b;
  }
 }
 @Aspect public static class ProbeAspect {
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
    return value;
   }catch(Throwable e){error=e;throw e;}finally{record(label,start,error);PHASE.get().pop();}
  }
  @Around("execution(* com.ticketing.ticket.ReservationDispatch.claim(..))") public Object claim(ProceedingJoinPoint p)throws Throwable{
   Trace temporary=new Trace("claim");CURRENT.set(temporary);PHASE.get().push("ReservationDispatch.claim");long start=System.nanoTime();
   try{Object value=p.proceed();if(value instanceof Map){Map<?,?> job=(Map<?,?>)value;Trace t=trace(job.get("request_id").toString());t.claimStart=start;t.claimEnd=System.nanoTime();t.spans.addAll(temporary.spans);}return value;}finally{clear();}
  }
 }
 @RestController public static class Endpoint {
  final ReservationIngress ingress;final HikariDataSource data;final MeterRegistry metrics;
  Endpoint(ReservationIngress i,HikariDataSource d,MeterRegistry m){ingress=i;data=d;metrics=m;}
  @GetMapping("/api/admin/lock-diag") public Map<String,Object> snapshot(@RequestParam(value="full",defaultValue="false") boolean full){
   Map<String,Object> m=new LinkedHashMap<>();List<Object> traces=new ArrayList<>();for(Trace t:ALL.values()){
    if(full){traces.add(t);continue;}Map<String,Object> v=new LinkedHashMap<>();v.put("requestId",t.requestId);v.put("enqueue",t.enqueue);v.put("consumerStart",t.consumerStart);v.put("consumerEnd",t.consumerEnd);v.put("decision",t.decision);v.put("redisHeld",t.redisHeld);v.put("orderInserted",t.orderInserted);v.put("locked",t.locked);traces.add(v);
   }m.put("traces",traces);m.put("transformed",TRANSFORMED);m.put("forbiddenCalls",FORBIDDEN.get());
   ThreadPoolExecutor pool=(ThreadPoolExecutor)field(ingress,"executor");m.put("ingressQueue",pool.getQueue().size());m.put("ingressActive",pool.getActiveCount());m.put("ingressCore",pool.getCorePoolSize());m.put("ingressCapacity",pool.getQueue().size()+pool.getQueue().remainingCapacity());
   com.zaxxer.hikari.HikariPoolMXBean hp=data.getHikariPoolMXBean();Map<String,Object> hikari=new LinkedHashMap<>();hikari.put("active",hp.getActiveConnections());hikari.put("idle",hp.getIdleConnections());hikari.put("waiting",hp.getThreadsAwaitingConnection());hikari.put("max",data.getMaximumPoolSize());m.put("hikari",hikari);
   Map<String,Object> gauges=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:metrics.getMeters())if(meter instanceof io.micrometer.core.instrument.Gauge&&(meter.getId().getName().startsWith("threadpool.")||meter.getId().getName().startsWith("reservation.ingress.")))gauges.put(meter.getId().getName()+meter.getId().getTags(),((io.micrometer.core.instrument.Gauge)meter).value());m.put("gauges",gauges);
   Map<String,Object> counters=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:metrics.getMeters())if(meter instanceof io.micrometer.core.instrument.Counter)counters.put(meter.getId().getName()+meter.getId().getTags(),((io.micrometer.core.instrument.Counter)meter).count());m.put("counters",counters);
   List<Object> gc=new ArrayList<>();for(GarbageCollectorMXBean g:ManagementFactory.getGarbageCollectorMXBeans())gc.add(Arrays.asList(g.getName(),g.getCollectionCount(),g.getCollectionTime()));m.put("gc",gc);m.put("nano",System.nanoTime());return m;
  }
 }
}
