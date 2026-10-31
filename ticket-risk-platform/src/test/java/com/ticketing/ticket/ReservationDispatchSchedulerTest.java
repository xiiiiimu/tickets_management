package com.ticketing.ticket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservationDispatchSchedulerTest {
    List<ReservationDispatchScheduler> owned=new ArrayList<>();
    ReservationDispatchScheduler scheduler(boolean isolation,boolean wake){
        ReservationDispatchScheduler s=new ReservationDispatchScheduler(new MockEnvironment().withProperty("ticketing.reservations-v2.dispatch.isolated",String.valueOf(isolation)).withProperty("ticketing.reservations-v2.dispatch.wakeup",String.valueOf(wake)).withProperty("ticketing.reservations-v2.poll-ms","60000"),new SimpleMeterRegistry());owned.add(s);return s;
    }
    @AfterEach void cleanup(){for(ReservationDispatchScheduler s:owned)s.close();}
    @Test void defaultOffCreatesNoControlExecutor(){
        ReservationDispatchScheduler s=new ReservationDispatchScheduler(new MockEnvironment(),new SimpleMeterRegistry());owned.add(s);
        s.start(()->fail("Disabled control must not run"));assertFalse(s.isIsolated());assertNull(ReflectionTestUtils.getField(s,"executor"));
    }
    @Test void wakeCannotSilentlyEnableIsolation(){assertThrows(IllegalArgumentException.class,()->scheduler(false,true));}
    @Test void isolatedWorkNeverRunsOnCallerAndSurvivesFailure()throws Exception{
        ReservationDispatchScheduler s=scheduler(true,true);CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);AtomicInteger runs=new AtomicInteger();AtomicReference<String> name=new AtomicReference<>();
        s.start(()->{name.set(Thread.currentThread().getName());if(runs.incrementAndGet()==1){first.countDown();throw new IllegalStateException("expected test failure");}second.countDown();});
        assertTrue(first.await(3,TimeUnit.SECONDS));commitWake(s);assertTrue(second.await(3,TimeUnit.SECONDS));assertEquals("sale-dispatch-control",name.get());
    }
    static void commitWake(ReservationDispatchScheduler s){
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        try{s.afterCommitWake();for(TransactionSynchronization sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();}
        finally{TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void notificationsAreCoalescedAndControlQueueIsBounded()throws Exception{
        ReservationDispatchScheduler s=scheduler(true,true);CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1),second=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        s.start(()->{if(calls.incrementAndGet()==1){running.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}else second.countDown();});
        try{
            assertTrue(running.await(3,TimeUnit.SECONDS));for(int n=0;n<100;n++)commitWake(s);
            ScheduledThreadPoolExecutor pool=(ScheduledThreadPoolExecutor)ReflectionTestUtils.getField(s,"executor");assertTrue(pool.getQueue().size()<=2);
            release.countDown();assertTrue(second.await(3,TimeUnit.SECONDS));assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test void stoppedControlDoesNotThrowFromCommittedCallback(){ReservationDispatchScheduler s=scheduler(true,true);s.start(()->{});s.close();assertDoesNotThrow(()->commitWake(s));}
    @Test void afterCommitRegistrationRejectsNonTransactionalCall(){assertThrows(IllegalStateException.class,()->scheduler(true,true).afterCommitWake());}
}
