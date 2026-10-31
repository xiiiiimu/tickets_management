package com.ticketing.ticket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Registrar contract: each caller gets exactly its own item's outcome; timeouts are not rollbacks. */
class AttemptRegistrarTest {
    ReservationAttemptStore store=mock(ReservationAttemptStore.class);SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptRegistrar registrar;
    MockEnvironment env=new MockEnvironment();
    AttemptRegistrar start(){registrar=new AttemptRegistrar(store,metrics,env);registrar.start();return registrar;}
    @AfterEach void stop(){if(registrar!=null)registrar.stop();}
    static ReserveRequest request(int i){ReserveRequest r=new ReserveRequest();r.setRequestId("r"+i);r.setUserId((long)i);return r;}
    @SuppressWarnings("unchecked") static List<ReservationAttemptStore.Registration> items(org.mockito.invocation.InvocationOnMock call){return (List<ReservationAttemptStore.Registration>)call.getArgument(0);}

    @Test void concurrentCallersAreGroupedAndEachGetsItsOwnResultOrFailure()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms","20");
        AtomicInteger batches=new AtomicInteger();
        doAnswer(call->{batches.incrementAndGet();for(ReservationAttemptStore.Registration r:items(call)){
            String id=r.request.getRequestId();
            if(id.endsWith("3"))ReflectionSupport.fail(r,new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
            else ReflectionSupport.ok(r,Collections.singletonMap("id","job-"+id));
        }return null;}).when(store).registerBatch(any());
        start();
        ExecutorService callers=Executors.newFixedThreadPool(12);CountDownLatch go=new CountDownLatch(1);
        try{
            List<Future<Object>> results=new ArrayList<>();
            for(int i=0;i<12;i++){int n=i;results.add(callers.submit(()->{go.await();try{return registrar.register(request(n),"ip","fp",1).get("id");}catch(AttemptAdmissionException e){return e.status;}}));}
            go.countDown();
            for(int i=0;i<12;i++){Object v=results.get(i).get(5,TimeUnit.SECONDS);if(("r"+i).endsWith("3"))assertEquals(429,v);else assertEquals("job-r"+i,v);}
        }finally{callers.shutdownNow();}
        assertTrue(batches.get()<12,"requests must share transactions, got "+batches.get());
    }
    @Test void wholeBatchFailureIsRethrownToEveryCallerUnchanged()throws Exception{
        RuntimeException commit=new org.springframework.transaction.TransactionSystemException("commit unknown");
        doThrow(commit).when(store).registerBatch(any());start();
        RuntimeException seen=assertThrows(RuntimeException.class,()->registrar.register(request(1),"ip","fp",1));
        assertSame(commit,seen);
    }
    /** Records the definite fate the registrar reports for one request. */
    static final class Fate implements AttemptRegistrar.Settlement {
        final List<String> events=Collections.synchronizedList(new ArrayList<>());
        public void committed(boolean newWaiting){events.add(newWaiting?"committed_waiting":"committed_existing");}
        public void notRegistered(){events.add("not_registered");}
    }
    static Set<String> ids(List<ReservationAttemptStore.Registration> items){Set<String> ids=new HashSet<>();for(ReservationAttemptStore.Registration r:items)ids.add(r.request.getRequestId());return ids;}
    static Map<String,Object> created(String id){Map<String,Object> job=new HashMap<>();job.put("id",id);job.put("newlyCreated",true);return job;}

    @Test void cancelledWhileQueuedNeverReachesMysqlAndIsADefiniteRefusal()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","150");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms","0");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Set<String> executed=ConcurrentHashMap.newKeySet();
        doAnswer(call->{executed.addAll(ids(items(call)));entered.countDown();release.await(5,TimeUnit.SECONDS);
            for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,created("job-"+r.request.getRequestId()));return null;}).when(store).registerBatch(any());
        when(store.registrarCancelled()).thenReturn(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        start();ExecutorService callers=Executors.newFixedThreadPool(2);
        try{
            Fate first=new Fate(),second=new Fate();
            Future<Map<String,Object>> one=callers.submit(()->registrar.register(request(1),"ip","fp",1,first));
            assertTrue(entered.await(2,TimeUnit.SECONDS)); // registrar thread is now busy in batch #1
            AttemptAdmissionException refused=assertThrows(AttemptAdmissionException.class,()->registrar.register(request(2),"ip","fp",1,second));
            assertEquals(429,refused.status);assertEquals(Collections.singletonList("not_registered"),second.events);
            assertEquals(0.0,metrics.get("attempt.register.batch.pending").gauge().value(),"cancelled item removed from the buffer");
            release.countDown();assertEquals("job-r1",one.get(3,TimeUnit.SECONDS).get("id"));
            Thread.sleep(300); // give the registrar every chance to (wrongly) run the cancelled item
            assertEquals(Collections.singleton("r1"),executed,"a cancelled request is never handed to MySQL");
            assertEquals(Collections.singletonList("committed_waiting"),first.events);
            assertEquals(1.0,metrics.get("attempt.register.batch.cancelled").counter().count());
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void takenRequestWaitsForTheCommitAndReturnsTheReceipt()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","100");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms","3000");
        doAnswer(call->{Thread.sleep(400);for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,created("late"));return null;}).when(store).registerBatch(any());
        start();Fate fate=new Fate();
        assertEquals("late",registrar.register(request(1),"ip","fp",1,fate).get("id"),"slow commit after TAKEN is a receipt, not a 503");
        assertEquals(1.0,metrics.get("attempt.register.batch.taken.wait").counter().count());
        assertEquals(Collections.singletonList("committed_waiting"),fate.events);
        verify(store,never()).registrarCancelled();
    }
    @Test void takenRequestStillRunningAfterTheBoundIsUnknownNotFailed()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","100");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms","200");
        CountDownLatch release=new CountDownLatch(1);
        // Models a database call the 3s Spring timeout did NOT stop.
        doAnswer(call->{release.await(5,TimeUnit.SECONDS);for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,created("late"));return null;}).when(store).registerBatch(any());
        start();Fate fate=new Fate();
        AttemptAdmissionException slow=assertThrows(AttemptAdmissionException.class,()->registrar.register(request(1),"ip","fp",1,fate));
        assertEquals(503,slow.status);assertEquals("VALIDATION_UNAVAILABLE",slow.code);
        assertTrue(fate.events.isEmpty(),"unknown is never reported as a fate");
        assertEquals(1.0,metrics.get("attempt.register.batch.taken.timeout").counter().count());
        release.countDown();
        long until=System.currentTimeMillis()+2000;while(fate.events.isEmpty()&&System.currentTimeMillis()<until)Thread.sleep(5);
        assertEquals(Collections.singletonList("committed_waiting"),fate.events,"the late commit is still settled after the caller left");
    }
    @Test void wholeBatchFailureIsUnknownAndNeverSettled(){
        doThrow(new org.springframework.transaction.TransactionSystemException("commit unknown")).when(store).registerBatch(any());start();Fate fate=new Fate();
        assertThrows(org.springframework.transaction.TransactionSystemException.class,()->registrar.register(request(1),"ip","fp",1,fate));
        assertTrue(fate.events.isEmpty());
    }
    @Test void perItemRollbackIsDefinitelyNotRegistered(){
        doAnswer(call->{for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.fail(r,new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));return null;}).when(store).registerBatch(any());
        start();Fate fate=new Fate();
        assertEquals(429,assertThrows(AttemptAdmissionException.class,()->registrar.register(request(1),"ip","fp",1,fate)).status);
        assertEquals(Collections.singletonList("not_registered"),fate.events);
    }
    @Test void existingJobOrAliasIsCommittedWithoutNewWaitingRow(){
        doAnswer(call->{for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,Collections.singletonMap("id","existing"));return null;}).when(store).registerBatch(any());
        start();Fate fate=new Fate();registrar.register(request(1),"ip","fp",1,fate);
        assertEquals(Collections.singletonList("committed_existing"),fate.events);
    }
    @Test void cancelVersusTakeRaceNeverLosesOrDuplicatesARequest()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","100");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms","2000");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-size","4");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms","1");
        Map<String,Integer> executed=new ConcurrentHashMap<>();Random random=new Random(7);
        doAnswer(call->{for(ReservationAttemptStore.Registration r:items(call))executed.merge(r.request.getRequestId(),1,Integer::sum);
            Thread.sleep(random.nextInt(60)); // batches straddle the 100ms wait in both directions
            for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,created("job-"+r.request.getRequestId()));return null;}).when(store).registerBatch(any());
        when(store.registrarCancelled()).thenAnswer(x->new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        start();ExecutorService callers=Executors.newFixedThreadPool(24);
        try{
            int n=240;List<Future<String>> outcomes=new ArrayList<>();List<Fate> fates=new ArrayList<>();
            for(int i=0;i<n;i++){int k=i;Fate f=new Fate();fates.add(f);outcomes.add(callers.submit(()->{
                try{return "ok:"+registrar.register(request(k),"ip","fp",1,f).get("id");}catch(AttemptAdmissionException e){return e.status+":"+e.code;}}));}
            int ok=0,cancelled=0;
            for(int i=0;i<n;i++){
                String v=outcomes.get(i).get(10,TimeUnit.SECONDS);String id="r"+i;
                if(v.startsWith("ok:")){ok++;assertEquals("ok:job-"+id,v);assertEquals(1,executed.get(id));assertEquals(Collections.singletonList("committed_waiting"),fates.get(i).events);}
                else{assertEquals("429:SYSTEM_BUSY",v,"only a definite cancellation may refuse here");cancelled++;
                    assertNull(executed.get(id),"cancelled "+id+" reached MySQL");assertEquals(Collections.singletonList("not_registered"),fates.get(i).events);}
            }
            Thread.sleep(200);
            assertEquals(ok,executed.size(),"no cancelled request executed later");
            assertTrue(ok>0&&cancelled>0,"race exercised both sides: ok="+ok+" cancelled="+cancelled);
        }finally{callers.shutdownNow();}
    }
    @Test void fullLocalBufferRefusesBeforeDatabaseWork()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-queue","1");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-size","1");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","2000");
        CountDownLatch release=new CountDownLatch(1);CountDownLatch entered=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,Collections.singletonMap("id","x"));return null;}).when(store).registerBatch(any());
        when(store.registrarFull()).thenReturn(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        start();
        ExecutorService callers=Executors.newFixedThreadPool(2);
        try{
            Future<?> first=callers.submit(()->registrar.register(request(1),"ip","fp",1));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> second=callers.submit(()->registrar.register(request(2),"ip","fp",1)); // occupies the single buffer slot
            long filled=System.currentTimeMillis()+2000;
            while(metrics.get("attempt.register.batch.pending").gauge().value()<1&&System.currentTimeMillis()<filled)Thread.sleep(5);
            assertEquals(1.0,metrics.get("attempt.register.batch.pending").gauge().value());
            long stop=System.currentTimeMillis()+2000;
            AttemptAdmissionException third=null;
            while(third==null&&System.currentTimeMillis()<stop){
                try{registrar.register(request(3),"ip","fp",1);}catch(AttemptAdmissionException e){third=e;}
                if(third==null)Thread.sleep(10);
            }
            assertNotNull(third);assertEquals(429,third.status);
            release.countDown();first.get(3,TimeUnit.SECONDS);second.get(3,TimeUnit.SECONDS);
        }finally{callers.shutdownNow();}
    }
    @Test void backlogThatCannotCommitWithinTheWaitIsRefusedUpFront()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-size","2");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","500");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-backlog-guard","true");
        AtomicInteger calls=new AtomicInteger();CountDownLatch release=new CountDownLatch(1);
        doAnswer(call->{if(calls.incrementAndGet()==1)Thread.sleep(150);else release.await(5,TimeUnit.SECONDS);
            for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,Collections.singletonMap("id","x"));return null;}).when(store).registerBatch(any());
        when(store.registrarFull()).thenReturn(new AttemptAdmissionException(429,"SYSTEM_BUSY","busy"));
        start();
        registrar.register(request(0),"ip","fp",1); // learns ~150ms per batch
        assertEquals(4,registrar.backlogLimit(),"(400ms of the wait / ~150ms per batch) = 2 batches x 2 per batch");
        ExecutorService callers=Executors.newFixedThreadPool(10);
        try{
            List<Future<?>> waiting=new ArrayList<>();
            for(int i=1;i<=8;i++){int n=i;waiting.add(callers.submit(()->{try{registrar.register(request(n),"ip","fp",1);}catch(AttemptAdmissionException ignored){}}));}
            long until=System.currentTimeMillis()+2000;
            while(metrics.get("attempt.register.batch.pending").gauge().value()<registrar.backlogLimit()&&System.currentTimeMillis()<until)Thread.sleep(5);
            AttemptAdmissionException refused=assertThrows(AttemptAdmissionException.class,()->registrar.register(request(99),"ip","fp",1));
            assertEquals(429,refused.status);
            release.countDown();for(Future<?> f:waiting)f.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();callers.shutdownNow();}
    }
    @Test void invalidSettingsAreRejectedAtStartup(){
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","5000");
        assertThrows(IllegalArgumentException.class,()->new AttemptRegistrar(store,metrics,env));
    }
    @Test void stoppedRegistrarNeverAcceptsWork(){
        start();registrar.stop();Fate fate=new Fate();
        AttemptAdmissionException refused=assertThrows(AttemptAdmissionException.class,()->registrar.register(request(1),"ip","fp",1,fate));
        assertEquals(503,refused.status);assertEquals("SYSTEM_BUSY",refused.code,"definite refusal, not an unknown outcome");
        assertEquals(Collections.singletonList("not_registered"),fate.events);
        registrar=null;
    }
    @Test void stopFinishesTheRunningBatchAndRefusesOnlyWhatWasNeverTaken()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","2000");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-linger-ms","0");
        CountDownLatch entered=new CountDownLatch(1);Set<String> executed=ConcurrentHashMap.newKeySet();
        doAnswer(call->{executed.addAll(ids(items(call)));entered.countDown();Thread.sleep(300);
            for(ReservationAttemptStore.Registration r:items(call))ReflectionSupport.ok(r,created("job-"+r.request.getRequestId()));return null;}).when(store).registerBatch(any());
        start();ExecutorService callers=Executors.newFixedThreadPool(2);
        try{
            Fate first=new Fate(),second=new Fate();
            Future<Object> one=callers.submit(()->registrar.register(request(1),"ip","fp",1,first).get("id"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<Object> two=callers.submit(()->{try{return registrar.register(request(2),"ip","fp",1,second).get("id");}catch(AttemptAdmissionException e){return e.status+":"+e.code;}});
            long until=System.currentTimeMillis()+2000;while(metrics.get("attempt.register.batch.pending").gauge().value()<1&&System.currentTimeMillis()<until)Thread.sleep(5);
            registrar.stop();
            assertEquals("job-r1",one.get(3,TimeUnit.SECONDS),"the batch already in MySQL completes with its real outcome");
            assertEquals("503:SYSTEM_BUSY",two.get(3,TimeUnit.SECONDS));
            assertEquals(Collections.singleton("r1"),executed);
            assertEquals(Collections.singletonList("committed_waiting"),first.events);assertEquals(Collections.singletonList("not_registered"),second.events);
            registrar=null;
        }finally{callers.shutdownNow();}
    }
}

/** Package-level helper so tests can fill a Registration the same way registerBatch does. */
final class ReflectionSupport {
    static void ok(ReservationAttemptStore.Registration r,Map<String,Object> result){r.result=result;r.failure=null;}
    static void fail(ReservationAttemptStore.Registration r,RuntimeException failure){r.result=null;r.failure=failure;}
}
