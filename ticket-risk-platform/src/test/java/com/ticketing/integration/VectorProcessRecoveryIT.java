package com.ticketing.integration;

import com.ticketing.ticket.*;
import com.ticketing.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real process kill plus two independent worker JVMs, using the current owned schema/Redis DB. */
class VectorProcessRecoveryIT extends VectorReservationIT {
    Process worker(boolean pause,Path log)throws Exception{
        String javaExe=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();String original=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path isolated=log.getParent().resolve(log.getFileName().toString()+".classes"),source=Paths.get(benchmark.VectorWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("benchmark");Files.createDirectories(isolated.resolve("benchmark"));
        try(java.util.stream.Stream<Path> files=Files.list(source)){files.filter(p->p.getFileName().toString().startsWith("VectorWorker")&&p.toString().endsWith(".class")).forEach(p->{try{Files.copy(p,isolated.resolve("benchmark").resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new RuntimeException(e);}});}
        String cp=isolated+File.pathSeparator+Arrays.stream(original.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(p->!p.replace('\\','/').endsWith("/target/test-classes")).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        return new ProcessBuilder(javaExe,"-Xmx192m","-Duser.timezone=UTC","-Dvector.pause="+pause,"-cp",cp,"benchmark.VectorWorker").redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    @Test void processKillInLuaMysqlGapRecoversExactlyOnceWithTwoWorkerInstances()throws Exception{
        isolated();Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);reliable(r);
        Map<String,Object> granted=attempts.claim();assertNotNull(granted);Map<String,Object> qualified=admission.classify(granted,armory.runtimePlan(s.session,s.sku));assertNotNull(attempts.decided(granted,qualified));transactions.prepare(granted);
        Path dir=Paths.get(System.getProperty("vector.result.dir"));Files.createDirectories(dir);Process first=null,a=null,b=null;
        try{
            Path log=dir.resolve("paused-worker.log");first=worker(true,log);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(System.nanoTime()<end&&first.isAlive()&&(!Files.exists(log)||!new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8).contains("VECTOR_PAUSED "+r.getRequestId())))TimeUnit.MILLISECONDS.sleep(100);
            assertTrue(first.isAlive());assertTrue(new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8).contains("VECTOR_PAUSED "+r.getRequestId()));
            assertEquals(11,redisStock(s));assertEquals(16,stock(s));String marker=InventoryService.prefix(s.sku)+"reservation:"+queue.byRequest(r.getRequestId()).get("reservation_id");Object expiry=redis.opsForHash().get(marker,"expiresAt");
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));a=worker(false,dir.resolve("worker-a.log"));b=worker(false,dir.resolve("worker-b.log"));
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);Map<String,Object> done;
            do{done=queue.byRequest(r.getRequestId());if(!Arrays.asList("WAITING","PROCESSING").contains(done.get("state")))break;assertTrue(a.isAlive()||b.isAlive());TimeUnit.MILLISECONDS.sleep(100);}while(System.nanoTime()<end);
            assertEquals("RESERVED",done.get("state"));assertEquals(11,stock(s));assertEquals(11,redisStock(s));assertEquals(expiry,redis.opsForHash().get(marker,"expiresAt"));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));
            List<String> requests=new ArrayList<>();for(int i=0;i<4;i++){ReserveRequest next=quantity(s,1);reliable(next);requests.add(next.getRequestId());}
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            do{int active=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='PROCESSING'",Integer.class);assertTrue(active<=4);long remaining=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('WAITING','PROCESSING')",Long.class);if(remaining==0)break;TimeUnit.MILLISECONDS.sleep(100);}while(System.nanoTime()<end);
            for(String id:requests)assertEquals("RESERVED",queue.byRequest(id).get("state"));assertEquals(7,stock(s));assertEquals(7,redisStock(s));assertEquals(5,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));
        }finally{for(Process p:Arrays.asList(first,a,b))if(p!=null&&p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(10,TimeUnit.SECONDS));}}
    }
}
