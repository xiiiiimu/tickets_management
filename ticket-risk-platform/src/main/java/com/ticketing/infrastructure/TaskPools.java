package com.ticketing.infrastructure;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import io.micrometer.core.instrument.*;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PreDestroy;
@Component
public class TaskPools {
 private final Map<String,ThreadPoolTaskExecutor> pools=new LinkedHashMap<>();
 public TaskPools(Environment env,MeterRegistry registry){for(String name:Arrays.asList("outbox","compensation","reservation","timeout","cache","risk","autopay","sale","gateway")){
  ThreadPoolTaskExecutor p=new ThreadPoolTaskExecutor();String prefix="ticketing.pools."+name+".";
  p.setCorePoolSize(env.getProperty(prefix+"core",Integer.class,2));p.setMaxPoolSize(env.getProperty(prefix+"max",Integer.class,4));p.setQueueCapacity(env.getProperty(prefix+"queue",Integer.class,100));p.setThreadNamePrefix(name+"-");
  AtomicLong rejects=new AtomicLong();p.setRejectedExecutionHandler((r,e)->{rejects.incrementAndGet();throw new RejectedExecutionException(name);});
  p.setTaskDecorator(r->{Map<String,String> context=MDC.getCopyOfContextMap();return ()->{if(context!=null)MDC.setContextMap(context);try{r.run();}finally{MDC.clear();}};});p.setWaitForTasksToCompleteOnShutdown(true);p.setAwaitTerminationSeconds(15);p.initialize();pools.put(name,p);
  Gauge.builder("threadpool.active",p,ThreadPoolTaskExecutor::getActiveCount).tag("pool",name).register(registry);
  Gauge.builder("threadpool.queue",p,x->x.getThreadPoolExecutor().getQueue().size()).tag("pool",name).register(registry);
  Gauge.builder("threadpool.core",p,ThreadPoolTaskExecutor::getCorePoolSize).tag("pool",name).register(registry);
  Gauge.builder("threadpool.max",p,ThreadPoolTaskExecutor::getMaxPoolSize).tag("pool",name).register(registry);
  Gauge.builder("threadpool.completed",p,x->x.getThreadPoolExecutor().getCompletedTaskCount()).tag("pool",name).register(registry);
  Gauge.builder("threadpool.reject",rejects,AtomicLong::get).tag("pool",name).register(registry);
 }}
 public void execute(String pool,Runnable task){pools.get(pool).execute(task);}
 public void resize(String name,int core,int max){if(!pools.containsKey(name)||core<1||max<core||max>128)throw new IllegalArgumentException();ThreadPoolTaskExecutor p=pools.get(name);if(max>p.getMaxPoolSize()){p.setMaxPoolSize(max);p.setCorePoolSize(core);}else{p.setCorePoolSize(core);p.setMaxPoolSize(max);}}
 @PreDestroy public void close(){pools.values().forEach(ThreadPoolTaskExecutor::shutdown);}
}
