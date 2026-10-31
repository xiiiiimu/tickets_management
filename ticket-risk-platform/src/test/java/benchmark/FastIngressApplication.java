package benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.config.MvcConfig;
import com.ticketing.config.WebExceptionAdvice;
import com.ticketing.infrastructure.Db;
import com.ticketing.ticket.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Test classpath ONLY. No component scan, transaction engine, Armory replacement or stock operations. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName={
 "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
 "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
 "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
 "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
 "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"})
@Import({MvcConfig.class,WebExceptionAdvice.class,ReservationV2Controller.class,ReservationIngress.class,FastIngressDiagnostics.class})
@EnableScheduling
@EnableAspectJAutoProxy
public class FastIngressApplication {
 public static void main(String[] args){SpringApplication.run(FastIngressApplication.class,args);}
 @Bean Consumer consumer(MeterRegistry registry,Environment env,StringRedisTemplate redis){
  if(!env.getProperty("fast.benchmark.isolated",Boolean.class,false)
    ||!"127.0.0.1".equals(env.getProperty("server.address"))
    ||!"26379".equals(env.getProperty("spring.redis.port")))throw new IllegalStateException("Explicit isolated loopback benchmark required");
  int db=env.getRequiredProperty("spring.redis.database",Integer.class);
  if(db<1||db>255||!env.getRequiredProperty("fast.benchmark.owner").equals(redis.opsForValue().get("credit-risk:fast-ingress-owner")))
   throw new IllegalStateException("Fresh owned test Redis DB required");
  return new Consumer(registry,env.getProperty("fast.benchmark.consumer-ms",Long.class,0L));
 }
 @Bean ReservationIntentService fingerprints(ObjectMapper mapper){return new ReservationIntentService(new Db(null,mapper),null,null);}
 @Bean EntryTiming entryTiming(){return new EntryTiming();}
 @Bean OncePerRequestFilter startClock(){return new OncePerRequestFilter(){
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
   if(!"POST".equals(req.getMethod())||!"/api/tickets/reservations".equals(req.getRequestURI())){chain.doFilter(req,res);return;}
   EntryTiming.START.set(System.nanoTime());try{chain.doFilter(req,res);}finally{EntryTiming.START.remove();}
  }
 };}
 public static final class Consumer extends ReservationV2Service {
  final AtomicLong consumed=new AtomicLong(),checksum=new AtomicLong();volatile long pauseUntil;volatile long delayMs;
  volatile String thread="";
  Consumer(MeterRegistry registry,long delay){super(null,null,null,null,null,null,registry);setDelay(delay);}
  void setDelay(long value){if(value<0||value>100)throw new IllegalArgumentException("Test consumer delay 0..100ms");delayMs=value;}
  @Override public boolean isAccepting(){return true;}
  @Override public void dispatch(){} // No SQL scheduler in this test context. Actual ingress workers consume below.
  @Override public Map<String,Object> submit(ReserveRequest r,String ip){
   if(!Thread.currentThread().getName().startsWith("sale-ingress-"))throw new IllegalStateException("Consumer ran on HTTP thread");
   thread=Thread.currentThread().getName();
   while(System.currentTimeMillis()<pauseUntil){LockSupport.parkNanos(1000000);if(Thread.currentThread().isInterrupted())throw new IllegalStateException("stopped");}
   if(delayMs>0)LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
   if(Thread.currentThread().isInterrupted())throw new IllegalStateException("stopped");
   checksum.addAndGet(Objects.hash(r.getRequestId(),r.getUserId(),r.getTicketSkuId(),r.getQuantity()));consumed.incrementAndGet();
   return Collections.singletonMap("mode","ISOLATED_CONSUMED_NO_RISK_NO_STOCK");
  }
  @Override public Map<String,Object> lookupRequest(String id,long user){return null;}
  @Override public Map<String,Object> status(String id,long user){throw new BusinessException(501,"Isolated benchmark has no reservation or order");}
 }
 @Aspect public static class EntryTiming {
  static final ThreadLocal<Long> START=new ThreadLocal<>();
  final LongAdder calls=new LongAdder(),beforeNanos=new LongAdder(),controllerNanos=new LongAdder();
  @Around("execution(* com.ticketing.ticket.ReservationV2Controller.create(..))")
  public Object around(ProceedingJoinPoint point)throws Throwable{
   long now=System.nanoTime();Long begin=START.get();if(begin!=null)beforeNanos.add(now-begin);
   try{return point.proceed();}finally{calls.increment();controllerNanos.add(System.nanoTime()-now);}
  }
 }
 @RestController public static class Probe {
  final Consumer consumer;final ReservationIngress ingress;final MeterRegistry metrics;final EntryTiming timing;
  final org.springframework.beans.factory.ObjectProvider<FastIngressDiagnostics.DiagnosticEndpoint> diagnostic;
  Probe(Consumer c,ReservationIngress i,MeterRegistry m,EntryTiming t,org.springframework.beans.factory.ObjectProvider<FastIngressDiagnostics.DiagnosticEndpoint> d){consumer=c;ingress=i;metrics=m;timing=t;diagnostic=d;}
  private ThreadPoolExecutor executor(){return (ThreadPoolExecutor)ReflectionTestUtils.getField(ingress,"executor");}
  @GetMapping("/api/admin/fast-ingress/probe") public Map<String,Object> snapshot(){
   Map<String,Object> v=new LinkedHashMap<>();v.put("mode","ISOLATED_TEST_ONLY");v.put("consumed",consumer.consumed.get());v.put("checksum",consumer.checksum.get());v.put("consumerThread",consumer.thread);
   v.put("queue",executor().getQueue().size());v.put("active",executor().getActiveCount());v.put("workers",executor().getCorePoolSize());v.put("capacity",executor().getQueue().size()+executor().getQueue().remainingCapacity());v.put("consumerMs",consumer.delayMs);
   v.put("timedCalls",timing.calls.sum());v.put("beforeControllerNanos",timing.beforeNanos.sum());v.put("controllerNanos",timing.controllerNanos.sum());
   Map<String,Double> counters=new LinkedHashMap<>();metrics.getMeters().forEach(m->{if(m instanceof io.micrometer.core.instrument.Counter){String key=m.getId().getName()+m.getId().getTags();counters.put(key,((io.micrometer.core.instrument.Counter)m).count());}});v.put("counters",counters);return v;
  }
  @PostMapping("/api/admin/fast-ingress/pause") public Map<String,Object> pause(@RequestParam("paused") boolean paused){consumer.pauseUntil=paused?System.currentTimeMillis()+10000:0;return snapshot();}
  @PostMapping("/api/admin/fast-ingress/reset") public Map<String,Object> reset(){
   Object entries=ReflectionTestUtils.getField(ingress,"entries");synchronized(entries){
    if(executor().getActiveCount()!=0||!executor().getQueue().isEmpty()||!((Map<?,?>)ReflectionTestUtils.getField(ingress,"perUser")).isEmpty())throw new BusinessException(409,"Consumer must drain before test fixture reset");
    ((Map<?,?>)entries).clear(); // ONLY completed volatile fixture entries; never changes capacity, tokens or production data.
   }if(diagnostic.getIfAvailable()!=null)FastIngressDiagnostics.reset();return snapshot();
  }
  @GetMapping("/api/admin/fast-ingress/diagnostics") public Map<String,Object> diagnostic(@RequestParam(value="traces",defaultValue="false") boolean traces){
   FastIngressDiagnostics.DiagnosticEndpoint d=diagnostic.getIfAvailable();return d==null?Collections.singletonMap("enabled",false):d.snapshot(traces);
  }
 }
}
