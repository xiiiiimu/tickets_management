package benchmark;

import java.lang.management.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.handler.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import com.ticketing.ticket.ReserveRequest;

/** Opt-in diagnostics on the TEST launcher only. Delegates all real handlers unchanged. */
@Configuration
@ConditionalOnProperty(name="fast.benchmark.diagnostics",havingValue="true")
public class FastIngressDiagnostics {
 static final ThreadLocal<Trace> CURRENT=new ThreadLocal<>();
 static final Map<String,Totals> TOTALS=new ConcurrentHashMap<>();
 static final ArrayDeque<Map<String,Object>> SAMPLES=new ArrayDeque<>();
 static final AtomicLong COMPLETED=new AtomicLong(),ACTIVE=new AtomicLong(),OMITTED=new AtomicLong();
 static final class Totals {
  final LongAdder count=new LongAdder(),nanos=new LongAdder();final AtomicLong max=new AtomicLong();
  void add(long n){count.increment();nanos.add(n);max.accumulateAndGet(n,Math::max);}
 }
 static final class Trace {
  String id;long outer,inner,innerEnd,firstInterceptor,lastInterceptor,firstBind,lastBind,controller,controllerEnd;
  final Map<String,Long> phases=new LinkedHashMap<>();final List<Map<String,Object>> calls=new ArrayList<>();
  void phase(String key,long n){phases.merge(key,Math.max(0,n),Long::sum);}
  void call(String name,long begin,long end){Map<String,Object> v=new LinkedHashMap<>();v.put("name",name);v.put("begin_offset_ns",begin-outer);v.put("end_offset_ns",end-outer);calls.add(v);}
 }
 static void measured(String key,long n){Trace t=CURRENT.get();if(t!=null)t.phase(key,n);}
 public static void reset(){if(ACTIVE.get()!=0)throw new IllegalStateException("Diagnostic requests still active");TOTALS.clear();COMPLETED.set(0);OMITTED.set(0);synchronized(SAMPLES){SAMPLES.clear();}}
 @Bean FilterRegistrationBean<OncePerRequestFilter> diagnosticOuter(){
  FilterRegistrationBean<OncePerRequestFilter> bean=new FilterRegistrationBean<>();bean.setName("diagnosticOuter");bean.setOrder(Integer.MIN_VALUE+1);
  bean.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
   if(!"POST".equals(req.getMethod())||!"/api/tickets/reservations".equals(req.getRequestURI())){chain.doFilter(req,res);return;}
   Trace t=new Trace();t.id=req.getHeader("X-Fast-Trace");t.outer=System.nanoTime();CURRENT.set(t);ACTIVE.incrementAndGet();
   try{chain.doFilter(req,res);}finally{
    long end=System.nanoTime();t.phase("server_observed_filter_span",end-t.outer);
    if(t.inner>0)t.phase("filters_forward_outer_to_inner",t.inner-t.outer);
    if(t.firstInterceptor>0)t.phase("dispatcher_before_interceptors",t.firstInterceptor-t.inner);
    if(t.lastInterceptor>0&&t.firstInterceptor>0)t.phase("interceptors_pre_envelope",t.lastInterceptor-t.firstInterceptor);
    if(t.firstBind>0&&t.lastInterceptor>0)t.phase("adapter_before_binding",t.firstBind-t.lastInterceptor);
    if(t.lastBind>0)t.phase("binding_envelope",t.lastBind-t.firstBind);
    if(t.controller>0){t.phase("before_controller_outer_boundary",t.controller-t.outer);t.phase("binding_to_controller",t.controller-t.lastBind);}
    if(t.controllerEnd>0&&t.innerEnd>0)t.phase("response_and_mvc_completion",t.innerEnd-t.controllerEnd);
    if(t.innerEnd>0)t.phase("filters_unwind_inner_to_outer",end-t.innerEnd);
    t.phases.forEach((key,n)->TOTALS.computeIfAbsent(key,k->new Totals()).add(n));
    Map<String,Object> row=new LinkedHashMap<>();row.put("requestId",t.id);row.put("status",res.getStatus());row.put("server_start_nano",t.outer);row.put("server_end_nano",end);row.put("phases_ns",t.phases);row.put("calls",t.calls);
    synchronized(SAMPLES){if(SAMPLES.size()<4096)SAMPLES.add(row);else OMITTED.incrementAndGet();}
    COMPLETED.incrementAndGet();ACTIVE.decrementAndGet();CURRENT.remove();
   }
  }});return bean;
 }
 @Bean FilterRegistrationBean<OncePerRequestFilter> diagnosticInner(){
  FilterRegistrationBean<OncePerRequestFilter> bean=new FilterRegistrationBean<>();bean.setName("diagnosticInner");bean.setOrder(Integer.MAX_VALUE-10);
  bean.setFilter(new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
   Trace t=CURRENT.get();if(t!=null)t.inner=System.nanoTime();try{chain.doFilter(req,res);}finally{if(t!=null)t.innerEnd=System.nanoTime();}
  }});return bean;
 }
 @Bean static BeanPostProcessor diagnosticMvcWrappers(){return new BeanPostProcessor(){
  @SuppressWarnings("unchecked") public Object postProcessAfterInitialization(Object bean,String name)throws BeansException{
   if(bean instanceof AbstractHandlerMapping){
    List<HandlerInterceptor> list=(List<HandlerInterceptor>)ReflectionTestUtils.getField(bean,"adaptedInterceptors");
    for(int i=0;i<list.size();i++){
     HandlerInterceptor it=list.get(i);
     if(it instanceof MappedInterceptor){MappedInterceptor mapped=(MappedInterceptor)it;if(!(mapped.getInterceptor() instanceof TimedInterceptor))ReflectionTestUtils.setField(mapped,"interceptor",new TimedInterceptor(mapped.getInterceptor()));}
     else if(!(it instanceof TimedInterceptor))list.set(i,new TimedInterceptor(it));
    }
   }
   if(bean instanceof RequestMappingHandlerAdapter){
    RequestMappingHandlerAdapter adapter=(RequestMappingHandlerAdapter)bean;List<HandlerMethodArgumentResolver> wrapped=new ArrayList<>();
    for(HandlerMethodArgumentResolver delegate:adapter.getArgumentResolvers())wrapped.add(new HandlerMethodArgumentResolver(){
     public boolean supportsParameter(MethodParameter p){return delegate.supportsParameter(p);}
     public Object resolveArgument(MethodParameter p,ModelAndViewContainer mav,NativeWebRequest request,WebDataBinderFactory binder)throws Exception{
      Trace t=CURRENT.get();long begin=System.nanoTime();if(t!=null&&t.firstBind==0)t.firstBind=begin;
      try{return delegate.resolveArgument(p,mav,request,binder);}finally{if(t!=null){long end=System.nanoTime();t.lastBind=end;t.phase("binding_resolver."+p.getParameterType().getSimpleName(),end-begin);t.call("binding."+p.getParameterType().getSimpleName(),begin,end);}}
     }
    });adapter.setArgumentResolvers(wrapped);
   }return bean;
  }
 };}
 static final class TimedInterceptor implements AsyncHandlerInterceptor {
  final HandlerInterceptor delegate;final String name;
  TimedInterceptor(HandlerInterceptor d){delegate=d;name=d.getClass().getName();}
  public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler)throws Exception{
   Trace t=CURRENT.get();long begin=System.nanoTime();if(t!=null&&t.firstInterceptor==0)t.firstInterceptor=begin;
   try{return delegate.preHandle(req,res,handler);}finally{if(t!=null){long end=System.nanoTime();t.lastInterceptor=end;t.phase("interceptor_pre."+name,end-begin);t.call("interceptor_pre."+name,begin,end);}}
  }
  public void postHandle(HttpServletRequest q,HttpServletResponse r,Object h,ModelAndView m)throws Exception{delegate.postHandle(q,r,h,m);}
  public void afterCompletion(HttpServletRequest q,HttpServletResponse r,Object h,Exception e)throws Exception{delegate.afterCompletion(q,r,h,e);}
  public void afterConcurrentHandlingStarted(HttpServletRequest q,HttpServletResponse r,Object h)throws Exception{if(delegate instanceof AsyncHandlerInterceptor)((AsyncHandlerInterceptor)delegate).afterConcurrentHandlingStarted(q,r,h);}
 }
 @Bean StageAspect diagnosticStageAspect(){return new StageAspect();}
 @Aspect @Order(Integer.MAX_VALUE-1) public static class StageAspect {
  @Around("execution(* com.ticketing.ticket.ReservationV2Controller.create(..))") public Object controller(ProceedingJoinPoint point)throws Throwable{
   Trace t=CURRENT.get();long begin=System.nanoTime();if(t!=null){t.controller=begin;if(point.getArgs()[0] instanceof ReserveRequest)t.id=((ReserveRequest)point.getArgs()[0]).getRequestId();Long old=FastIngressApplication.EntryTiming.START.get();if(old!=null)t.phase("legacy_before_controller_boundary",begin-old);}
   try{return point.proceed();}finally{if(t!=null){t.controllerEnd=System.nanoTime();t.phase("controller_method",t.controllerEnd-begin);t.call("controller",begin,t.controllerEnd);}}
  }
  @Around("execution(* org.springframework.data.redis.connection.RedisConnectionFactory+.getConnection(..))") public Object redis(ProceedingJoinPoint point)throws Throwable{
   long begin=System.nanoTime();Object value;
   try{value=point.proceed();}finally{measured("redis_connection_handle_acquire",System.nanoTime()-begin);}
   if(!(value instanceof RedisConnection))return value;
   RedisConnection connection=(RedisConnection)value;
   return Proxy.newProxyInstance(RedisConnection.class.getClassLoader(),new Class<?>[]{RedisConnection.class},(proxy,method,args)->{
    Trace t=CURRENT.get();long start=System.nanoTime();
    try{return method.invoke(connection,args);}catch(InvocationTargetException e){throw e.getCause();}
    finally{if(t!=null&&Arrays.asList("hGetAll","pExpire","expire","eval","evalSha").contains(method.getName())){long end=System.nanoTime();t.phase("redis_command."+method.getName(),end-start);t.call("redis."+method.getName(),start,end);}}
   });
  }
 }
 @Bean DiagnosticEndpoint diagnosticEndpoint(ServletWebServerApplicationContext context){return new DiagnosticEndpoint(context);}
 // No @RestController here: nested annotated components are auto-registered by configuration parsing.
 public static class DiagnosticEndpoint {
  final ServletWebServerApplicationContext context;DiagnosticEndpoint(ServletWebServerApplicationContext c){context=c;}
  public Map<String,Object> snapshot(boolean samples){
   long snapshotStart=System.nanoTime();Map<String,Object> v=new LinkedHashMap<>();v.put("completed",COMPLETED.get());v.put("active",ACTIVE.get());v.put("omitted_samples",OMITTED.get());
   Map<String,Object> phases=new TreeMap<>();TOTALS.forEach((key,t)->{Map<String,Object> m=new LinkedHashMap<>();m.put("count",t.count.sum());m.put("nanos",t.nanos.sum());m.put("max_nanos",t.max.get());phases.put(key,m);});v.put("phases",phases);
   com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
   Map<String,Object> jvm=new LinkedHashMap<>();jvm.put("host",System.getenv("COMPUTERNAME"));jvm.put("nano",System.nanoTime());jvm.put("process_cpu_nanos",os.getProcessCpuTime());jvm.put("processors",os.getAvailableProcessors());jvm.put("heap_used",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());jvm.put("heap_committed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());jvm.put("physical_memory_total",os.getTotalPhysicalMemorySize());jvm.put("physical_memory_free",os.getFreePhysicalMemorySize());
   List<Map<String,Object>> gc=new ArrayList<>();for(GarbageCollectorMXBean g:ManagementFactory.getGarbageCollectorMXBeans()){Map<String,Object> m=new LinkedHashMap<>();m.put("name",g.getName());m.put("count",g.getCollectionCount());m.put("time_ms",g.getCollectionTime());gc.add(m);}jvm.put("gc",gc);v.put("jvm",jvm);
   Map<String,Object> tomcat=new LinkedHashMap<>();try{
    org.apache.coyote.AbstractProtocol<?> protocol=(org.apache.coyote.AbstractProtocol<?>)((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler();
    Executor executor=protocol.getExecutor();if(executor instanceof ThreadPoolExecutor){ThreadPoolExecutor pool=(ThreadPoolExecutor)executor;tomcat.put("active",pool.getActiveCount());tomcat.put("threads",pool.getPoolSize());tomcat.put("max_threads",pool.getMaximumPoolSize());tomcat.put("executor_queue",pool.getQueue().size());}
    Object endpoint=ReflectionTestUtils.invokeMethod(protocol,"getEndpoint");tomcat.put("connections",ReflectionTestUtils.invokeMethod(endpoint,"getConnectionCount"));
   }catch(Exception unsupported){tomcat.put("unavailable",unsupported.toString());}v.put("tomcat",tomcat);
   v.put("spring_security_filter_chain",context.containsBean("springSecurityFilterChain"));
   Map<String,Object> filters=new TreeMap<>();context.getServletContext().getFilterRegistrations().forEach((name,f)->filters.put(name,f.getClassName()));v.put("servlet_filters",filters);
   if(samples)synchronized(SAMPLES){v.put("traces",new ArrayList<>(SAMPLES));}v.put("snapshot_nanos",System.nanoTime()-snapshotStart);return v;
  }
 }
}
