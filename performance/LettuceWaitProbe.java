import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.event.command.*;
import io.netty.channel.Channel;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.lang.management.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jdk.jfr.*;

/** Standalone diagnostic only: no HTTP application, no production bean scan. */
public class LettuceWaitProbe {
 static final ObjectMapper JSON=new ObjectMapper();
 static final ThreadLocal<Call> CURRENT=new ThreadLocal<>();
 static final ConcurrentMap<Object,Call> PENDING=new ConcurrentHashMap<>();
 static final AtomicInteger HIGH_PENDING=new AtomicInteger(),FAILURES=new AtomicInteger();
 static final AtomicLong IDS=new AtomicLong();
 static final List<Map<String,Object>> CONNECTION_EVENTS=new CopyOnWriteArrayList<>();
 static volatile boolean measuring;
 static class Call { public long id,start,end,listenerStart,callback;public String op,callbackThread; public String error; }
 static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
 static void save(Path p,Object value)throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(p.toFile(),value);}
 static double percentile(List<Long> ns,double p){if(ns.isEmpty())return 0;Collections.sort(ns);return ns.get((int)Math.ceil(ns.size()*p)-1)/1e6;}
 static Map<String,Object> stats(List<Long> ns){return map("count",ns.size(),"p50_ms",percentile(ns,.5),"p95_ms",percentile(ns,.95),"p99_ms",percentile(ns,.99),"max_ms",percentile(ns,1));}
 static Object field(Object o,String name)throws Exception{for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){}throw new NoSuchFieldException(name);}
 static Map<String,Object> gc(){Map<String,Object> m=new LinkedHashMap<>();for(GarbageCollectorMXBean b:ManagementFactory.getGarbageCollectorMXBeans())m.put(b.getName(),map("count",b.getCollectionCount(),"ms",b.getCollectionTime()));return m;}
 static Call command(StringRedisTemplate redis,String key,String op){
  Call c=new Call();c.id=IDS.incrementAndGet();c.op=op;CURRENT.set(c);c.start=System.nanoTime();
  try{if(op.equals("HGETALL")){if(redis.opsForHash().entries(key).size()!=3)throw new IllegalStateException("fixture lost");}else if(!Boolean.TRUE.equals(redis.expire(key,30,TimeUnit.MINUTES)))throw new IllegalStateException("fixture missing");}
  catch(Throwable e){c.error=e.toString();FAILURES.incrementAndGet();}finally{c.end=System.nanoTime();CURRENT.remove();}return c;
 }
 static List<Call> load(StringRedisTemplate redis,String key,String mode,int concurrency,int seconds)throws Exception{
  List<Call> all=Collections.synchronizedList(new ArrayList<>());CountDownLatch ready=new CountDownLatch(concurrency),go=new CountDownLatch(1);AtomicLong end=new AtomicLong();List<Thread> threads=new ArrayList<>();
  for(int i=0;i<concurrency;i++){Thread t=new Thread(()->{ready.countDown();try{go.await();while(System.nanoTime()<end.get()&&FAILURES.get()<3){
    Call h=null;if(mode.equals("HGETALL")||mode.equals("PAIR")){h=command(redis,key,"HGETALL");all.add(h);}
    if(mode.equals("PEXPIRE")||mode.equals("PAIR")){Call p=command(redis,key,"PEXPIRE");all.add(p);if(h!=null)byPair.put(p.id,h);}
   }}catch(InterruptedException e){Thread.currentThread().interrupt();}},"redis-probe-worker-"+i);t.setDaemon(true);threads.add(t);t.start();}
  ready.await();end.set(System.nanoTime()+seconds*1000000000L);go.countDown();
  for(Thread t:threads)t.join((seconds+3)*1000L);
  if(threads.stream().anyMatch(Thread::isAlive))throw new IllegalStateException("bounded join timeout");return all;
 }
 public static void main(String[] args)throws Exception{
  Path out=Paths.get(args[0]);String key=args[1];
  try(ConfigurableApplicationContext context=new SpringApplicationBuilder(RedisAutoConfiguration.class).web(WebApplicationType.NONE).run("--spring.config.name=fast-ingress-benchmark","--spring.main.banner-mode=off","--logging.level.root=WARN")){
   StringRedisTemplate redis=context.getBean(StringRedisTemplate.class);LettuceConnectionFactory factory=context.getBean(LettuceConnectionFactory.class);
   if(!factory.getHostName().equals("127.0.0.1")||factory.getPort()!=26379||!key.startsWith("credit-risk:lettuce-probe:"))throw new IllegalStateException("isolated fixture required");
   AbstractRedisClient client=factory.getRequiredNativeClient();
   client.addListener(new CommandListener(){
    public void commandStarted(CommandStartedEvent e){Call c=CURRENT.get();if(c!=null){c.listenerStart=System.nanoTime();PENDING.put(e.getCommand(),c);HIGH_PENDING.accumulateAndGet(PENDING.size(),Math::max);}}
    public void commandSucceeded(CommandSucceededEvent e){Call c=PENDING.remove(e.getCommand());if(c!=null){c.callback=System.nanoTime();c.callbackThread=Thread.currentThread().getName();}}
    public void commandFailed(CommandFailedEvent e){PENDING.remove(e.getCommand());FAILURES.incrementAndGet();}
   });
   client.addListener(new RedisConnectionStateAdapter(){public void onRedisConnected(RedisChannelHandler<?,?> c,java.net.SocketAddress address){CONNECTION_EVENTS.add(map("event","connected","epoch_ms",System.currentTimeMillis()));}public void onRedisDisconnected(RedisChannelHandler<?,?> c){CONNECTION_EVENTS.add(map("event","disconnected","epoch_ms",System.currentTimeMillis()));}public void onRedisExceptionCaught(RedisChannelHandler<?,?> c,Throwable e){CONNECTION_EVENTS.add(map("event","exception","error",e.toString(),"epoch_ms",System.currentTimeMillis()));}});
   Map<String,String> token=new LinkedHashMap<>();token.put("id","901001");token.put("role","USER");token.put("nickName","lettuce-probe");redis.opsForHash().putAll(key,token);redis.expire(key,30,TimeUnit.MINUTES);
   Channel channel=null;String channelError=null;
   try(org.springframework.data.redis.connection.RedisConnection handle=factory.getConnection()){
    Object nativeCommands=handle.getNativeConnection();Object connection=nativeCommands.getClass().getMethod("getStatefulConnection").invoke(nativeCommands);
    Object writer=((RedisChannelHandler<?,?>)connection).getChannelWriter();
    for(int i=0;i<4;i++){try{channel=(Channel)field(writer,"channel");break;}catch(NoSuchFieldException e){writer=field(writer,"delegate");}}
   }catch(Exception e){channelError=e.toString();}
   save(out.resolve("connection.json"),map("shareNativeConnection",factory.getShareNativeConnection(),"host",factory.getHostName(),"port",factory.getPort(),"database",factory.getDatabase(),"timeout",factory.getClientConfiguration().getCommandTimeout().toString(),"clientConfiguration",factory.getClientConfiguration().getClass().getName(),"ioThreads",client.getResources().ioThreadPoolSize(),"computationThreads",client.getResources().computationThreadPoolSize(),"channel",String.valueOf(channel),"channelProbeError",channelError));
   try(Recording recording=new Recording(Configuration.getConfiguration("profile"))){
    recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(5)).withStackTrace();recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(5));recording.setMaxSize(96*1024*1024L);recording.start();
    cases:for(int concurrency:new int[]{1,8,32,64})for(String mode:new String[]{"HGETALL","PEXPIRE","PAIR"}){
     String label=mode+"-"+concurrency;load(redis,key,mode,concurrency,2);
     Files.write(out.resolve(label+".ready"),new byte[]{1});long gate=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
     while(!Files.exists(out.resolve(label+".go"))){if(System.nanoTime()>gate)throw new IllegalStateException("orchestrator timeout");Thread.sleep(20);}
     HIGH_PENDING.set(0);Map<String,Object> gcBefore=gc();com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();long cpu=os.getProcessCpuTime();
     List<Map<String,Object>> threadSamples=new CopyOnWriteArrayList<>();List<Long> heartbeat=new CopyOnWriteArrayList<>();AtomicBoolean stop=new AtomicBoolean();Channel observed=channel;
     ScheduledFuture<?> beat=null;
     if(channel!=null){long origin=System.nanoTime();AtomicLong tick=new AtomicLong();beat=channel.eventLoop().scheduleAtFixedRate(()->heartbeat.add(Math.max(0,System.nanoTime()-origin-tick.incrementAndGet()*100000000L)),100,100,TimeUnit.MILLISECONDS);}
     Thread observer=new Thread(()->{while(!stop.get()){try{Thread.sleep(500);Map<String,Integer> states=new TreeMap<>();List<Map<String,Object>> stacks=new ArrayList<>();for(ThreadInfo t:ManagementFactory.getThreadMXBean().dumpAllThreads(false,false,12)){String n=t.getThreadName();if(n.startsWith("lettuce-")||n.startsWith("redis-probe-worker-")){states.merge(n.replaceAll("[0-9]+","#")+" "+t.getThreadState(),1,Integer::sum);if(stacks.size()<8)stacks.add(map("thread",n,"state",t.getThreadState().toString(),"stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toArray()));}}threadSamples.add(map("epoch_ms",System.currentTimeMillis(),"states",states,"stacks",stacks,"listenerOutstanding",PENDING.size(),"eventLoopPendingTasks",observed!=null&&observed.eventLoop() instanceof SingleThreadEventExecutor?((SingleThreadEventExecutor)observed.eventLoop()).pendingTasks():null));}catch(Exception e){threadSamples.add(map("error",e.toString()));}}},"probe-observer");observer.setDaemon(true);observer.start();
     long startEpoch=System.currentTimeMillis(),start=System.nanoTime();List<Call> calls=load(redis,key,mode,concurrency,5);long end=System.nanoTime(),endEpoch=System.currentTimeMillis();long cpuDelta=os.getProcessCpuTime()-cpu;stop.set(true);observer.join(1500);if(beat!=null)beat.cancel(false);
     List<Long> h=new ArrayList<>(),p=new ArrayList<>(),total=new ArrayList<>(),listener=new ArrayList<>(),pre=new ArrayList<>(),post=new ArrayList<>();
     for(Call c:calls){if(c.op.equals("HGETALL"))h.add(c.end-c.start);else p.add(c.end-c.start);if(!mode.equals("PAIR"))total.add(c.end-c.start);if(c.listenerStart>0)pre.add(c.listenerStart-c.start);if(c.callback>0){listener.add(c.callback-c.listenerStart);post.add(c.end-c.callback);}}
     if(mode.equals("PAIR")){
      for(Call c:calls)if(c.op.equals("PEXPIRE")){Call prev=byPair.get(c.id);if(prev!=null)total.add(c.end-prev.start);}
     }
     save(out.resolve(label+"-calls.json"),calls);
     save(out.resolve(label+".json"),map("mode",mode,"concurrency",concurrency,"start_epoch_ms",startEpoch,"end_epoch_ms",endEpoch,"elapsed_seconds",(end-start)/1e9,"qps",total.size()/((end-start)/1e9),"hgetall",stats(h),"pexpire",stats(p),"operation",stats(total),"submit_to_listener",stats(pre),"listener_to_callback",stats(listener),"callback_to_return_signed",stats(post),"heartbeat_lateness",stats(heartbeat),"max_listener_outstanding",HIGH_PENDING.get(),"errors",FAILURES.get(),"gc_before",gcBefore,"gc_after",gc(),"cpu_cores",cpuDelta/(double)(end-start),"threads",threadSamples));
     Files.write(out.resolve(label+".done"),new byte[]{1});while(!Files.exists(out.resolve(label+".next"))){if(System.nanoTime()>gate+TimeUnit.SECONDS.toNanos(15))throw new IllegalStateException("next gate timeout");Thread.sleep(20);}
     byPair.clear();if(Files.exists(out.resolve("STOP"))||FAILURES.get()>=3)break cases;
    }
    recording.stop();recording.dump(out.resolve("standalone.jfr"));
   }finally{save(out.resolve("connection-events.json"),CONNECTION_EVENTS);redis.delete(key);}
  }
 }
 static final Map<Long,Call> byPair=new ConcurrentHashMap<>();
}
