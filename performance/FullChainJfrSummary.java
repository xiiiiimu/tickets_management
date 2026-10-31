import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class FullChainJfrSummary {
 static ObjectMapper json=new ObjectMapper();
 static class Bucket {public Map<String,Integer> counts=new TreeMap<>();public Map<String,List<Double>> times=new TreeMap<>();public Map<String,Integer> stacks=new HashMap<>();public Map<String,Integer> cpu=new HashMap<>();public List<Map<String,Object>> nettyBlocks=new ArrayList<>();public List<Map<String,Object>> pauses=new ArrayList<>();}
 static class Window {String label;long start,end;Bucket b=new Bucket();Window(String l,long s,long e){label=l;start=s;end=e;}}
 static List<Map.Entry<String,Integer>> top(Map<String,Integer> m,int n){List<Map.Entry<String,Integer>> v=new ArrayList<>(m.entrySet());v.sort((a,b)->Integer.compare(b.getValue(),a.getValue()));return v.subList(0,Math.min(n,v.size()));}
 static Map<String,Object> stats(List<Double> ns){Collections.sort(ns);Map<String,Object> m=new LinkedHashMap<>();m.put("count",ns.size());m.put("sum_thread_ms",ns.stream().mapToDouble(x->x).sum());for(int p:new int[]{50,95,99,100})m.put(p==100?"max_ms":"p"+p+"_ms",ns.get((int)Math.ceil(ns.size()*p/100d)-1));return m;}
 static void analyze(Path recording,List<Window> windows,Map<String,Object> output)throws Exception{
  try(RecordingFile file=new RecordingFile(recording)){while(file.hasMoreEvents()){
   RecordedEvent e=file.readEvent();String type=e.getEventType().getName();if(!Arrays.asList("jdk.ThreadPark","jdk.JavaMonitorEnter","jdk.GCPhasePause","jdk.ExecutionSample","jdk.SocketRead","jdk.SocketWrite").contains(type))continue;
   long stamp=e.getStartTime().toEpochMilli();RecordedThread t=type.equals("jdk.ExecutionSample")?e.getThread("sampledThread"):e.getThread();String name=t==null||t.getJavaName()==null?"":t.getJavaName();String cat=name.startsWith("lettuce-nioEventLoop")?"netty":name.startsWith("sale-ingress-")?"ingress":name.equals("sale-dispatch-control")?"control":name.startsWith("sale-")?"sale":name.startsWith("scheduling-")?"scheduler":name.startsWith("http-nio-")?"http":"other";
   StringBuilder stack=new StringBuilder();if(e.getStackTrace()!=null){int i=0;for(RecordedFrame f:e.getStackTrace().getFrames()){if(i++==16)break;if(stack.length()>0)stack.append(" > ");stack.append(f.getMethod().getType().getName()).append('.').append(f.getMethod().getName());}}
   for(Window w:windows){if(stamp<w.start||stamp>w.end)continue;Bucket b=w.b;if(type.equals("jdk.GCPhasePause")){Map<String,Object> pause=new LinkedHashMap<>();pause.put("epoch_ms",stamp);pause.put("duration_ms",e.getDuration().toNanos()/1e6);b.pauses.add(pause);}if(cat.equals("netty")&&(type.equals("jdk.ThreadPark")||type.equals("jdk.JavaMonitorEnter"))&&b.nettyBlocks.size()<64){Map<String,Object> detail=new LinkedHashMap<>();detail.put("thread",name);detail.put("type",type);detail.put("epoch_ms",stamp);detail.put("duration_ms",e.getDuration().toNanos()/1e6);detail.put("stack",stack.toString());b.nettyBlocks.add(detail);}String key=type+":"+cat;b.counts.merge(key,1,Integer::sum);
    if(type.equals("jdk.ExecutionSample"))b.cpu.merge(cat+":"+stack,1,Integer::sum);else{b.times.computeIfAbsent(key,k->new ArrayList<>()).add(e.getDuration().toNanos()/1e6);if(type.equals("jdk.ThreadPark")||type.equals("jdk.JavaMonitorEnter"))b.stacks.merge(key+":"+stack,1,Integer::sum);}
   }
  }}
  for(Window w:windows){Map<String,Object> m=new LinkedHashMap<>(),times=new TreeMap<>();w.b.times.forEach((k,v)->times.put(k,stats(v)));m.put("gc_pauses",w.b.pauses);m.put("netty_blocks",w.b.nettyBlocks);m.put("counts",w.b.counts);m.put("durations",times);m.put("top_wait_stacks",top(w.b.stacks,10));m.put("top_cpu_stacks",top(w.b.cpu,10));output.put(w.label,m);}
 }
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]);JsonNode raw=json.readTree(root.resolve("50-raw.json").toFile());JsonNode anchor=raw.get("after");long low=Long.MAX_VALUE,high=0;
  for(JsonNode t:raw.get("traces")){low=Math.min(low,t.get("enqueue").asLong());high=Math.max(high,t.get("locked").asLong());}
  long wall=anchor.get("wallMillis").asLong(),nano=anchor.get("nano").asLong();long from=wall+(low-nano)/1000000,to=wall+(high-nano)/1000000;
  Map<String,Object> result=new LinkedHashMap<>();analyze(root.resolve("full-chain.jfr"),Arrays.asList(new Window("single-50",from,to)),result);
  result.put("start_epoch_ms",from);result.put("end_epoch_ms",to);json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("jfr-analysis.json").toFile(),result);System.out.println("Offline JFR summary saved");
 }
}
