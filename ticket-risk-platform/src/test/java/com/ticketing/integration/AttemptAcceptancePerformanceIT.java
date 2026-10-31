package com.ticketing.integration;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.ticket.*;
import com.ticketing.risk.AttemptRiskAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One bounded batch, real network HTTP and production workers. Invoked once per fresh JVM/schema. */
@SpringBootTest(classes=TicketPlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.driver-class-name=com.mysql.jdbc.Driver","spring.datasource.initialization-mode=always",
 "ticketing.reservations-v2.enabled=true","ticketing.sale.armory.enabled=true","ticketing.inventory.bucket-count=16",
 "ticketing.reservations-v2.poll-ms=25","ticketing.risk.summary.enabled=true",
 "ticketing.mq.enabled=false","ticketing.jobs.enabled=false","ticketing.metrics.snapshot.enabled=false",
 "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
 "ticketing.pools.sale.core=4","ticketing.pools.sale.max=4","ticketing.pools.sale.queue=0"})
@TestPropertySource(properties={"ticketing.reservations-v2.attempts.enabled=true","ticketing.reservations-v2.attempts.ordered-lua=true","ticketing.reservations-v2.attempts.quick-review=true","ticketing.reservations-v2.dispatch.isolated=true","ticketing.reservations-v2.dispatch.wakeup=true","ticketing.inventory.vector-reservations=${controlled.vector:false}"})
@org.springframework.context.annotation.Import(AttemptAcceptancePerformanceIT.TimerDiagnostics.class)
class AttemptAcceptancePerformanceIT extends ReservationV2IT {
    @org.springframework.boot.test.context.TestConfiguration static class TimerDiagnostics {
        @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
        volatile long readyEpochMs;final java.util.concurrent.atomic.AtomicInteger readyEvents=new java.util.concurrent.atomic.AtomicInteger();
        @org.springframework.context.event.EventListener public void ready(org.springframework.boot.context.event.ApplicationReadyEvent event){if(event.getApplicationContext()==context){readyEpochMs=event.getTimestamp();readyEvents.incrementAndGet();}}
        @org.springframework.context.annotation.Bean io.micrometer.core.instrument.config.MeterFilter confirmationPercentiles(){return new io.micrometer.core.instrument.config.MeterFilter(){
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id,io.micrometer.core.instrument.distribution.DistributionStatisticConfig current){
                if(Arrays.asList("reservation.v2.transaction","attempt.ordered.lua.transaction","hikaricp.connections.acquire","attempt.transaction.duration").contains(id.getName()))return io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder().percentiles(.5,.95,.99).build().merge(current);return current;
            }
        };}
    }
    @LocalServerPort int port;
    @SpyBean ReservationV2Transactions measuredTransactions;
    @SpyBean AttemptRiskAdmission admission;
    // Existing singleton is spied after initialization; never stub initialize() or localWorkers().
    @SpyBean(name="reservationAttemptStore",proxyTargetAware=true) ReservationAttemptStore measuredAttempts;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ConfigurableApplicationContext context;
    @org.springframework.beans.factory.annotation.Autowired TimerDiagnostics timerDiagnostics;
    final Map<String,List<Double>> stages=new ConcurrentHashMap<>();
    final List<Map<String,Long>> dbBusyEvents=Collections.synchronizedList(new ArrayList<>());
    void record(String key,long start){stages.computeIfAbsent(key,k->Collections.synchronizedList(new ArrayList<>())).add((System.nanoTime()-start)/1e6);}
    Map<String,Object> post(ReserveRequest request,String token)throws Exception{
        long start=System.nanoTime();HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/tickets/reservations").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("authorization",token);
        byte[] bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(request);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}int status=c.getResponseCode();try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){if(in!=null){byte[] buffer=new byte[4096];while(in.read(buffer)!=-1){}}}
        Map<String,Object> value=new LinkedHashMap<>();value.put("requestId",request.getRequestId());value.put("status",status);value.put("startNano",start);value.put("endNano",System.nanoTime());value.put("ms",(System.nanoTime()-start)/1e6);return value;
    }
    String token(ReserveRequest request){String token="acceptance-"+com.ticketing.infrastructure.Db.id();Map<String,String> body=new HashMap<>();body.put("id",request.getUserId().toString());body.put("role","USER");redis.opsForHash().putAll("login:token:"+token,body);redis.expire("login:token:"+token,java.time.Duration.ofMinutes(5));return token;}
    void profile(ReserveRequest r,boolean review){db.jdbc.update("INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,expires_at) VALUES(?,'USER',?,?,?, ?,?)",com.ticketing.infrastructure.Db.id(),r.getUserId().toString(),review?"MEDIUM":"LOW",review?"REVIEW":"PASS",review?50:0,new Timestamp(System.currentTimeMillis()+600000));}
    static Map<String,Object> distribution(Collection<Double> data){Map<String,Object> v=new LinkedHashMap<>();List<Double> values=new ArrayList<>(data);Collections.sort(values);v.put("count",values.size());if(values.isEmpty())return v;for(int p:new int[]{50,95,99})v.put("p"+p,values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*p/100d)-1)));v.put("max",values.get(values.size()-1));return v;}
    Map<String,Double> counters(){Map<String,Double> result=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:registry.getMeters())if(meter instanceof io.micrometer.core.instrument.Counter&&meter.getId().getName().startsWith("attempt."))result.put(meter.getId().getName()+meter.getId().getTags(),((io.micrometer.core.instrument.Counter)meter).count());return result;}
    private static final long[] TRANSACTION_SLO_MS={1,5,10,25,50,100,250,500,1000,2000,5000};
    static Map<String,Object> transactionValues(io.micrometer.core.instrument.Timer timer,String coordinate,List<String> missing){
        io.micrometer.core.instrument.distribution.HistogramSnapshot snapshot=timer==null?null:timer.takeSnapshot();
        long count=snapshot==null?0:snapshot.count();Map<String,Object> values=new LinkedHashMap<>();values.put("count",count);
        values.put("mean",count==0?null:snapshot.mean(TimeUnit.MILLISECONDS));values.put("max",count==0?null:snapshot.max(TimeUnit.MILLISECONDS));
        for(String key:Arrays.asList("p50","p95","p99"))values.put(key,null);
        if(count>0)for(io.micrometer.core.instrument.distribution.ValueAtPercentile value:snapshot.percentileValues())values.put("p"+(int)(value.percentile()*100),value.value(TimeUnit.MILLISECONDS));
        Map<Double,Double> cumulative=new TreeMap<>();if(snapshot!=null)for(io.micrometer.core.instrument.distribution.CountAtBucket bucket:snapshot.histogramCounts())cumulative.put(bucket.bucket(TimeUnit.MILLISECONDS),bucket.count());
        List<Map<String,Object>> buckets=new ArrayList<>();for(long boundary:TRANSACTION_SLO_MS){
            Double bucketCount=cumulative.get((double)boundary);
            if(count>0&&bucketCount==null)missing.add(coordinate+":bucket="+boundary);
            Map<String,Object> bucket=new LinkedHashMap<>();bucket.put("le_ms",boundary);bucket.put("cumulative_count",bucketCount);buckets.add(bucket);
        }
        if(count>0)for(String key:Arrays.asList("p50","p95","p99"))if(values.get(key)==null)missing.add(coordinate+":"+key);
        values.put("buckets",buckets);values.put("export_status",count==0?"no_samples":buckets.stream().anyMatch(b->b.get("cumulative_count")==null)||values.get("p50")==null||values.get("p95")==null||values.get("p99")==null?"export_missing":"ok");return values;
    }
    Map<String,Object> transactionMetrics(List<String> missing){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String operation:Arrays.asList("register","claim","prepare")){
            Map<String,Object> outcomes=new LinkedHashMap<>();for(String outcome:Arrays.asList("success","failure")){
                Map<String,Object> phases=new LinkedHashMap<>();for(String phase:Arrays.asList("total","begin","body","completion"))phases.put(phase,transactionValues(registry.find("attempt.transaction.duration").tags("operation",operation,"outcome",outcome,"phase",phase).timer(),operation+"/"+outcome+"/"+phase,missing));
                outcomes.put(outcome,phases);
            }result.put(operation,outcomes);
        }return result;
    }
    Map<String,Double> taggedCounterDelta(Map<String,Double> delta,String name,String... tagNames){
        Map<String,Double> result=new TreeMap<>();for(io.micrometer.core.instrument.Meter meter:registry.getMeters())if(meter instanceof io.micrometer.core.instrument.Counter&&name.equals(meter.getId().getName())){
            List<String> parts=new ArrayList<>();for(String tag:tagNames)parts.add(Objects.toString(meter.getId().getTag(tag),"unknown"));
            result.merge(String.join("/",parts),delta.getOrDefault(meter.getId().getName()+meter.getId().getTags(),0d),Double::sum);
        }return result;
    }
    static Map<String,Object> dbBusyObserverOverlap(List<Map<String,Long>> events,List<Map<String,Object>> samples){
        int overlaps=0;
        for(Map<String,Long> event:events)for(Map<String,Object> sample:samples){
            if(!"completion_observer".equals(sample.get("source")))continue;
            Object start=sample.get("query_start_nano"),end=sample.get("query_end_nano");long nano=event.get("nano");
            if(start instanceof Number&&end instanceof Number&&nano>=((Number)start).longValue()&&nano<=((Number)end).longValue()){overlaps++;break;}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("events",events.size());result.put("overlapping_observer_query",overlaps);return result;
    }
    @SuppressWarnings("unchecked") Map<String,Object> validateRealContextCollector()throws Exception{
        Map<String,Object> checks=new LinkedHashMap<>();
        assertTrue(mockingDetails(measuredAttempts).isSpy());assertEquals(1,context.getBeansOfType(ReservationAttemptStore.class).size());assertSame(measuredAttempts,context.getBean("reservationAttemptStore"));
        assertSame(measuredAttempts,org.springframework.test.util.ReflectionTestUtils.getField(service,"attempts"));
        AttemptWorkerReadiness readiness=context.getBean(AttemptWorkerReadiness.class);long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!readiness.isReady()&&System.nanoTime()<stop)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));assertTrue(readiness.isReady());
        org.springframework.beans.factory.ObjectProvider<ReservationAttemptStore> provider=(org.springframework.beans.factory.ObjectProvider<ReservationAttemptStore>)org.springframework.test.util.ReflectionTestUtils.getField(readiness,"stores");assertSame(measuredAttempts,provider.getObject());
        assertNull(mockingDetails(measuredAttempts).getMockCreationSettings().getConstructorArgs());
        long init=mockingDetails(measuredAttempts).getInvocations().stream().filter(i->"initialize".equals(i.getMethod().getName())).count();assertEquals(1,init,"Only the existing readiness recheck should be observed on the post-initialization spy");
        Object diagnostics=org.springframework.test.util.ReflectionTestUtils.getField(measuredAttempts,"diagnostics");
        Object original=mockingDetails(measuredAttempts).getMockCreationSettings().getSpiedInstance();assertNotNull(original);assertSame(diagnostics,org.springframework.test.util.ReflectionTestUtils.getField(original,"diagnostics"));
        java.util.function.IntSupplier workers=(java.util.function.IntSupplier)org.springframework.test.util.ReflectionTestUtils.getField(diagnostics,"localWorkers");
        java.util.concurrent.atomic.AtomicInteger active=(java.util.concurrent.atomic.AtomicInteger)org.springframework.test.util.ReflectionTestUtils.getField(service,"attemptWorkers");assertEquals(active.get(),workers.getAsInt());
        checks.put("singleton_injection_provider_callback",true);checks.put("initialize_calls_observed_on_spy",init);checks.put("spy_uses_constructor",false);checks.put("ready",true);
        java.util.concurrent.atomic.AtomicReference<Object> realReturn=new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call->{Map<String,Long> event=new LinkedHashMap<>();event.put("nano",System.nanoTime());event.put("epoch_ms",System.currentTimeMillis());dbBusyEvents.add(event);Object value=call.callRealMethod();realReturn.set(value);return value;}).when(measuredAttempts).dbBusy();
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        int before=dbBusyEvents.size();long nano=System.nanoTime(),epoch=System.currentTimeMillis();AttemptAdmissionException refusal=measuredAttempts.dbBusy();long afterNano=System.nanoTime(),afterEpoch=System.currentTimeMillis();
        assertSame(realReturn.get(),refusal);assertEquals("SYSTEM_BUSY",refusal.code);assertEquals(429,refusal.status);assertEquals(before+1,dbBusyEvents.size());
        Map<String,Long> recorded=dbBusyEvents.get(before);assertTrue(recorded.get("nano")>=nano&&recorded.get("nano")<=afterNano);assertTrue(recorded.get("epoch_ms")>=epoch&&recorded.get("epoch_ms")<=afterEpoch);
        // Fault only the diagnostic collaborator in this disposable context; never alter the actual pool/config.
        Object source=org.springframework.test.util.ReflectionTestUtils.getField(diagnostics,"source");com.zaxxer.hikari.HikariDataSource failing=mock(com.zaxxer.hikari.HikariDataSource.class);RuntimeException marker=new IllegalStateException("collector validation injected diagnostic failure");when(failing.getHikariPoolMXBean()).thenThrow(marker);
        try{org.springframework.test.util.ReflectionTestUtils.setField(diagnostics,"source",failing);assertSame(marker,assertThrows(RuntimeException.class,()->measuredAttempts.dbBusy()));}
        finally{org.springframework.test.util.ReflectionTestUtils.setField(diagnostics,"source",source);}
        assertSame(source,org.springframework.test.util.ReflectionTestUtils.getField(diagnostics,"source"));assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());checks.put("real_return_identity_exception_identity_and_no_transaction",true);checks.put("prewarm_probe_events_excluded",dbBusyEvents.size()-before);
        List<String> missing=new ArrayList<>();Map<String,Object> empty=transactionValues(null,"prepare/failure/total",missing);assertEquals(0L,empty.get("count"));assertNull(empty.get("p95"));assertEquals("no_samples",empty.get("export_status"));assertTrue(missing.isEmpty());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry probe=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try{io.micrometer.core.instrument.Timer broken=probe.timer("deliberately_missing_export_configuration");broken.record(1,TimeUnit.MILLISECONDS);Map<String,Object> value=transactionValues(broken,"probe/success/total",missing);assertEquals("export_missing",value.get("export_status"));assertTrue(missing.contains("probe/success/total:p95"));assertTrue(missing.contains("probe/success/total:bucket=5"));}
        finally{probe.close();}checks.put("zero_samples_not_fault_and_true_missing_detected",true);
        List<Map<String,Object>> queries=new ArrayList<>();for(long[] bounds:new long[][]{{100,200},{200,300}}){Map<String,Object> q=new LinkedHashMap<>();q.put("source","completion_observer");q.put("query_start_nano",bounds[0]);q.put("query_end_nano",bounds[1]);queries.add(q);}
        List<Map<String,Long>> events=new ArrayList<>();for(long value:new long[]{99,100,150,200,300,301})events.add(Collections.singletonMap("nano",value));assertEquals(4,dbBusyObserverOverlap(events,queries).get("overlapping_observer_query"));checks.put("closed_interval_overlap_and_once_per_event",true);
        assertEquals(init,mockingDetails(measuredAttempts).getInvocations().stream().filter(i->"initialize".equals(i.getMethod().getName())).count());assertEquals(1,timerDiagnostics.readyEvents.get());return checks;
    }
    @SuppressWarnings("unchecked") static void expectTransaction(Map<String,Object> transactions,String operation,boolean expected,List<String> missing){
        if(!expected)return;Map<String,Object> success=(Map<String,Object>)((Map<String,Object>)transactions.get(operation)).get("success");
        for(String phase:Arrays.asList("total","begin","body","completion")){Map<String,Object> values=(Map<String,Object>)success.get(phase);if(((Number)values.get("count")).longValue()==0){values.put("export_status","export_missing");missing.add(operation+"/success/"+phase+":export_missing");}}
    }
    /** Runs only with the explicitly invoked IT; no business registry samples or extra HTTP requests. */
    static void verifyTransactionTimerReregistration(){
        io.micrometer.core.instrument.simple.SimpleMeterRegistry probe=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try{
            probe.config().meterFilter(new TimerDiagnostics().confirmationPercentiles());
            java.time.Duration[] slo=Arrays.stream(TRANSACTION_SLO_MS).mapToObj(java.time.Duration::ofMillis).toArray(java.time.Duration[]::new);
            io.micrometer.core.instrument.Timer warm=io.micrometer.core.instrument.Timer.builder("attempt.transaction.duration").tags("operation","register","outcome","success","phase","total").serviceLevelObjectives(slo).register(probe);warm.record(7,TimeUnit.MILLISECONDS);
            for(io.micrometer.core.instrument.Timer timer:new ArrayList<>(probe.find("attempt.transaction.duration").timers()))probe.remove(timer);
            io.micrometer.core.instrument.Timer first=io.micrometer.core.instrument.Timer.builder("attempt.transaction.duration").tags("operation","register","outcome","success","phase","total").serviceLevelObjectives(slo).register(probe);first.record(3,TimeUnit.MILLISECONDS);
            assertNotSame(warm,first);assertEquals(1,first.count());assertEquals(3d,first.totalTime(TimeUnit.MILLISECONDS),.000001);transactionValues(first,"register/success/total",new ArrayList<>());
        }finally{probe.close();}
    }
    @Test void oneControlledBatch()throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"));
        verifyTransactionTimerReregistration();
        Map<String,Object> collectorValidation=Boolean.getBoolean("attempt.export.validation")?validateRealContextCollector():Collections.emptyMap();
        doAnswer(call->{Map<String,Long> event=new LinkedHashMap<>();event.put("nano",System.nanoTime());event.put("epoch_ms",System.currentTimeMillis());dbBusyEvents.add(event);return call.callRealMethod();}).when(measuredAttempts).dbBusy();
        doAnswer(call->{long start=System.nanoTime();try{return call.callRealMethod();}finally{record("lua",start);}}).when(inventory).reserveRuntime(any(),anyLong(),anyLong());
        doAnswer(call->{long start=System.nanoTime();try{return call.callRealMethod();}finally{record("reserve_method_including_ordered_turn_and_confirmation",start);}}).when(measuredTransactions).reserve(any());
        doAnswer(call->{long start=System.nanoTime();Object result=call.callRealMethod();record("qualification_"+((Map<?,?>)result).get("profileDecision"),start);return result;}).when(admission).classify(any(),any());
        Sale s=sale("PASS");List<ReserveRequest> requests=new ArrayList<>();Map<String,String> tokens=new HashMap<>();
        for(int i=0;i<53;i++){ReserveRequest r=request(s);r.setRequestId("acceptance-request-"+i);profile(r,i%2==1);requests.add(r);tokens.put(r.getRequestId(),token(r));}
        for(int i=0;i<3;i++){ReserveRequest warm=requests.get(i);assertEquals(202,post(warm,tokens.get(warm.getRequestId())).get("status"));long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!"RESERVED".equals(queue.byRequest(warm.getRequestId()).get("state"))&&System.nanoTime()<stop)TimeUnit.MILLISECONDS.sleep(50);assertEquals("RESERVED",queue.byRequest(warm.getRequestId()).get("state"));}
        stages.clear();for(String name:Arrays.asList("reservation.v2.transaction","attempt.ordered.lua.transaction")){io.micrometer.core.instrument.Timer timer=registry.find(name).timer();if(timer!=null)registry.remove(timer);}
        // Production obtains a timer on each record; do not retain removed timer references.
        for(io.micrometer.core.instrument.Timer timer:new ArrayList<>(registry.find("attempt.transaction.duration").timers()))registry.remove(timer);
        dbBusyEvents.clear();
        Map<String,Double> countersBefore=counters();
        requests=new ArrayList<>(requests.subList(3,53));Set<String> ids=new HashSet<>();for(ReserveRequest r:requests)ids.add(r.getRequestId());
        long clockStart=System.currentTimeMillis();Timestamp dbNow=db.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class);long clockEnd=System.currentTimeMillis(),offset=dbNow.getTime()-(clockStart+clockEnd)/2;
        List<Map<String,Object>> http=new ArrayList<>(),samples=Collections.synchronizedList(new ArrayList<>());Map<String,Long> terminal=new ConcurrentHashMap<>();ExecutorService clients=Executors.newFixedThreadPool(10);long begin=System.nanoTime();long cpu0=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();long gc0=ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum();
        Path dir=Paths.get(System.getProperty("vector.result.dir"));Files.createDirectories(dir);com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.concurrent.atomic.AtomicBoolean pressure=new java.util.concurrent.atomic.AtomicBoolean();ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(()->{Map<String,Object> sample=new LinkedHashMap<>();com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();long free=os.getFreePhysicalMemorySize();sample.put("nano",System.nanoTime());sample.put("processCpuNs",os.getProcessCpuTime());sample.put("freePhysicalBytes",free);sample.put("heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());if(free<1024L*1024*1024)pressure.set(true);if(dataSource instanceof com.zaxxer.hikari.HikariDataSource){com.zaxxer.hikari.HikariPoolMXBean pool=((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean();sample.put("hikariActive",pool.getActiveConnections());sample.put("hikariWaiting",pool.getThreadsAwaitingConnection());}io.micrometer.core.instrument.Gauge active=registry.find("threadpool.active").tag("pool","sale").gauge();if(active!=null)sample.put("saleActive",active.value());samples.add(sample);},0,100,TimeUnit.MILLISECONDS);
        ScheduledExecutorService completionObserver=Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.atomic.AtomicReference<Throwable> observationFailure=new java.util.concurrent.atomic.AtomicReference<>();
        completionObserver.scheduleAtFixedRate(()->{try{
            Map<String,Object> querySample=new LinkedHashMap<>();querySample.put("source","completion_observer");querySample.put("query_start_epoch_ms",System.currentTimeMillis());querySample.put("query_start_nano",System.nanoTime());
            List<Map<String,Object>> observedRows;
            try{observedRows=db.jdbc.queryForList("SELECT request_id,state FROM tb_reservation_job WHERE session_id=?",s.session);}
            finally{querySample.put("query_end_nano",System.nanoTime());querySample.put("query_end_epoch_ms",System.currentTimeMillis());samples.add(querySample);}
            long observed=System.currentTimeMillis()+offset;
            for(Map<String,Object> row:observedRows)if(ids.contains(row.get("request_id"))&&!Arrays.asList("WAITING","PROCESSING").contains(row.get("state")))terminal.putIfAbsent(row.get("request_id").toString(),observed);
        }catch(Throwable failure){observationFailure.compareAndSet(null,failure);}},0,200,TimeUnit.MILLISECONDS);
        try{
        try{List<Future<Map<String,Object>>> pending=new ArrayList<>();for(ReserveRequest r:requests)pending.add(clients.submit(()->post(r,tokens.get(r.getRequestId()))));for(Future<Map<String,Object>> f:pending)http.add(f.get(20,TimeUnit.SECONDS));}finally{clients.shutdownNow();}
        mapper.writeValue(dir.resolve("http.json").toFile(),http);
        long httpEnd=http.stream().mapToLong(x->((Number)x.get("endNano")).longValue()).max().orElse(begin);long accepted=http.stream().filter(x->Integer.valueOf(202).equals(x.get("status"))).count();assertTrue(http.stream().allMatch(x->Arrays.asList(202,429,503).contains(x.get("status"))),"Unexpected HTTP response");
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);List<Map<String,Object>> rows=new ArrayList<>();
        do{rows=db.jdbc.queryForList("SELECT * FROM tb_reservation_job WHERE session_id=?",s.session);long observed=System.currentTimeMillis()+offset;int live=0;
            for(Map<String,Object> row:rows)if(ids.contains(row.get("request_id"))){if(Arrays.asList("WAITING","PROCESSING").contains(row.get("state")))live++;else terminal.putIfAbsent(row.get("request_id").toString(),observed);}
            Map<String,Object> sample=new LinkedHashMap<>();sample.put("time",observed);sample.put("live",live);if(dataSource instanceof com.zaxxer.hikari.HikariDataSource){com.zaxxer.hikari.HikariPoolMXBean pool=((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean();sample.put("hikariActive",pool.getActiveConnections());sample.put("hikariWaiting",pool.getThreadsAwaitingConnection());}sample.put("heap",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());samples.add(sample);
            if(live==0)break;TimeUnit.MILLISECONDS.sleep(200);
        }while(System.nanoTime()<stop);
        completionObserver.shutdownNow();assertTrue(completionObserver.awaitTermination(6,TimeUnit.SECONDS));assertNull(observationFailure.get(),"Concurrent completion observer failed");
        assertTrue(terminal.size()>=accepted);List<Double> waits=new ArrayList<>(),processing=new ArrayList<>(),e2e=new ArrayList<>();Map<String,Integer> states=new TreeMap<>();long firstAccepted=Long.MAX_VALUE,lastHeld=0;int held=0;
        for(Map<String,Object> row:rows)if(ids.contains(row.get("request_id"))){String id=row.get("request_id").toString(),state=row.get("state").toString();assertFalse(Arrays.asList("WAITING","PROCESSING").contains(state),"Unfinished registered task");states.merge(state,1,Integer::sum);long created=((Timestamp)row.get("created_at")).getTime();firstAccepted=Math.min(firstAccepted,created);if(row.get("processing_started_at")!=null){long started=((Timestamp)row.get("processing_started_at")).getTime();waits.add((double)(started-created));processing.add((double)Math.max(0,terminal.get(id)-started));}if("RESERVED".equals(state)){held++;lastHeld=Math.max(lastHeld,terminal.get(id));e2e.add((double)Math.max(0,terminal.get(id)-created));}assertNull(row.get("lease_token"));}
        assertEquals(100-3-held,stock(s));assertEquals(stock(s),redisStock(s));assertEquals(3+held,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=? AND status='REQUESTED'",Integer.class,s.sku));
        List<Map<String,Object>> retries=new ArrayList<>();for(ReserveRequest r:requests)if(terminal.containsKey(r.getRequestId())){retries.add(post(r,tokens.get(r.getRequestId())));if(retries.size()==4)break;}assertEquals(4,retries.size());assertEquals(stock(s),redisStock(s));assertEquals(3+held,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));
        Map<String,Object> result=new LinkedHashMap<>();double seconds=(httpEnd-begin)/1e9;result.put("vector",inventory.vectorEnabled());result.put("requests",50);result.put("concurrency",10);result.put("warmup_requests",3);result.put("http_seconds",seconds);result.put("http_qps",50/seconds);result.put("accepted_qps",accepted/seconds);result.put("accepted",accepted);result.put("rejected",50-accepted);result.put("states",states);result.put("held",held);result.put("held_window_ms",held==0?null:lastHeld-firstAccepted);result.put("held_tps",held==0?0:held*1000d/Math.max(1,lastHeld-firstAccepted));result.put("post_ms",distribution(http.stream().map(x->((Number)x.get("ms")).doubleValue()).collect(java.util.stream.Collectors.toList())));result.put("waiting_ms",distribution(waits));result.put("processing_ms",distribution(processing));result.put("held_e2e_ms",distribution(e2e));Map<String,Object> summaries=new TreeMap<>();stages.forEach((k,v)->summaries.put(k,distribution(v)));result.put("stages",summaries);result.put("cpu_nanos",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime()-cpu0);result.put("gc_collection_ms",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum()-gc0);result.put("db_clock_offset_ms",offset);result.put("db_clock_calibration_rtt_ms",clockEnd-clockStart);result.put("completion_observation_interval_ms",200);result.put("all_invariants_passed",true);
        Map<String,Integer> codes=new TreeMap<>();for(Map<String,Object> response:http)codes.merge(response.get("status").toString(),1,Integer::sum);result.put("http_codes",codes);result.put("registered",terminal.size());result.put("in_flight",0);result.put("unsent",0);result.put("resource_pressure",pressure.get());
        result.put("rejected",codes.getOrDefault("429",0));result.put("unavailable",codes.getOrDefault("503",0));
        Map<String,Double> counterDelta=counters();counterDelta.replaceAll((key,value)->value-countersBefore.getOrDefault(key,0d));result.put("counter_delta",counterDelta);
        List<String> transactionMissing=new ArrayList<>();Map<String,Object> transactions=transactionMetrics(transactionMissing);
        expectTransaction(transactions,"register",accepted>0,transactionMissing);expectTransaction(transactions,"claim",held>0,transactionMissing);expectTransaction(transactions,"prepare",held>0,transactionMissing);result.put("transaction_ms",transactions);
        Map<String,Object> exportCheck=new LinkedHashMap<>();exportCheck.put("ok",transactionMissing.isEmpty());exportCheck.put("status",transactionMissing.isEmpty()?"ok":"export_missing");exportCheck.put("missing",transactionMissing);result.put("transaction_export_check",exportCheck);
        Map<String,Double> rejections=taggedCounterDelta(counterDelta,"attempt.admission.rejected.internal","reason","detail");
        for(String key:Arrays.asList("DB_BUSY/pool_busy","CAPACITY_REJECT/waiting_budget","CAPACITY_REJECT/hard_capacity","CAPACITY_REJECT/user_inflight","CAPACITY_REJECT/alias_limit"))rejections.putIfAbsent(key,0d);
        result.put("admission_rejections",rejections);double classified=rejections.values().stream().mapToDouble(Double::doubleValue).sum();
        double legacyBusy=taggedCounterDelta(counterDelta,"attempt.admission.rejected","reason").getOrDefault("system_busy",0d);
        Map<String,Object> check=new LinkedHashMap<>();check.put("classified_total",classified);check.put("system_busy_total",legacyBusy);check.put("difference",classified-legacyBusy);check.put("matched",Double.compare(classified,legacyBusy)==0);result.put("admission_rejections_check",check);
        List<Map<String,Long>> busyEvents; synchronized(dbBusyEvents){busyEvents=new ArrayList<>(dbBusyEvents);}
        result.put("db_busy_event_count",busyEvents.size());double classifiedBusy=rejections.getOrDefault("DB_BUSY/pool_busy",0d);
        Map<String,Object> busyCheck=new LinkedHashMap<>();busyCheck.put("events",busyEvents.size());busyCheck.put("classified_db_busy",classifiedBusy);busyCheck.put("difference",busyEvents.size()-classifiedBusy);busyCheck.put("matched",Double.compare(busyEvents.size(),classifiedBusy)==0);result.put("db_busy_event_check",busyCheck);
        List<Map<String,Object>> observerSamples; synchronized(samples){observerSamples=new ArrayList<>(samples);}
        result.put("db_busy_observer_overlap",dbBusyObserverOverlap(busyEvents,observerSamples));
        Map<String,Object> startup=new LinkedHashMap<>();startup.put("context_start_epoch_ms",context.getStartupDate());startup.put("application_ready_event_epoch_ms",timerDiagnostics.readyEpochMs);startup.put("context_to_ready_ms",timerDiagnostics.readyEpochMs-context.getStartupDate());startup.put("jvm_to_ready_ms",timerDiagnostics.readyEpochMs-ManagementFactory.getRuntimeMXBean().getStartTime());startup.put("ready_events",timerDiagnostics.readyEvents.get());result.put("spring_startup",startup);result.put("collector_validation",collectorValidation);
        Map<String,Double> grants=taggedCounterDelta(counterDelta,"attempt.claim.granted","kind");grants.putIfAbsent("first",0d);grants.putIfAbsent("recovery",0d);result.put("claim_granted",grants);
        Map<String,Object> timerMetrics=new TreeMap<>();for(String name:Arrays.asList("reservation.v2.transaction","attempt.ordered.lua.transaction","hikaricp.connections.acquire")){io.micrometer.core.instrument.Timer timer=registry.find(name).timer();if(timer!=null){Map<String,Object> values=new LinkedHashMap<>();values.put("count",timer.count());values.put("mean",timer.mean(TimeUnit.MILLISECONDS));for(io.micrometer.core.instrument.distribution.ValueAtPercentile v:timer.takeSnapshot().percentileValues())values.put("p"+(int)(v.percentile()*100),v.value(TimeUnit.MILLISECONDS));timerMetrics.put(name,values);}}result.put("timer_ms",timerMetrics);
        sampler.shutdownNow();assertTrue(sampler.awaitTermination(2,TimeUnit.SECONDS));mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("batch.json").toFile(),result);mapper.writeValue(dir.resolve("http.json").toFile(),http);mapper.writeValue(dir.resolve("samples.json").toFile(),samples);mapper.writeValue(dir.resolve("jobs.json").toFile(),rows);mapper.writeValue(dir.resolve("retries.json").toFile(),retries);mapper.writeValue(dir.resolve("db_busy_events.json").toFile(),busyEvents);assertFalse(pressure.get(),"Host memory below unchanged 1GiB resource floor");
        }finally{sampler.shutdownNow();completionObserver.shutdownNow();}
    }
}
