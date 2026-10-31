package com.ticketing.sale;

import com.ticketing.ticket.AttemptAdmissionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedPlanLoadsTest {
    void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void sameKeyHasOneLeaderAndAllFollowersReallyReceiveItsResult()throws Exception{
        SimpleMeterRegistry metrics=new SimpleMeterRegistry();BoundedPlanLoads<String> loads=new BoundedPlanLoads<>(metrics,2,32);
        ExecutorService pool=Executors.newFixedThreadPool(16);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        try{
            Future<String> first=pool.submit(()->loads.get("sku",()->{calls.incrementAndGet();entered.countDown();await(release);return "plan";},10000));await(entered);
            List<Future<String>> followers=new ArrayList<>();for(int i=0;i<15;i++)followers.add(pool.submit(()->loads.get("sku",()->{throw new AssertionError("duplicate loader");},10000)));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(metrics.counter("sale.plan.merged").count()<15&&System.nanoTime()<until)Thread.yield();
            assertEquals(15,metrics.counter("sale.plan.merged").count());release.countDown();assertEquals("plan",first.get(5,TimeUnit.SECONDS));
            for(Future<String> f:followers)assertEquals("plan",f.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void differentKeysEnterLoadersConcurrentlyAndCapacityIsBounded()throws Exception{
        BoundedPlanLoads<String> loads=new BoundedPlanLoads<>(new SimpleMeterRegistry(),2,2);ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        try{
            Future<String> a=pool.submit(()->loads.get("a",()->{entered.countDown();await(release);return "a";},10000));
            Future<String> b=pool.submit(()->loads.get("b",()->{entered.countDown();await(release);return "b";},10000));await(entered);
            assertThrows(AttemptAdmissionException.class,()->loads.get("c",()->"c",100));release.countDown();assertEquals("a",a.get());assertEquals("b",b.get());
            assertEquals("c",loads.get("c",()->"c",100));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void failureDoesNotPoisonNextLoad(){
        BoundedPlanLoads<String> loads=new BoundedPlanLoads<>(new SimpleMeterRegistry(),1,1);RuntimeException original=new IllegalStateException("test failure");
        assertSame(original,assertThrows(IllegalStateException.class,()->loads.get("a",()->{throw original;},100)));assertEquals("recovered",loads.get("a",()->"recovered",100));
    }
    @Test void followerTimeoutDoesNotCancelLeaderOrLeakWaiterPermit()throws Exception{
        BoundedPlanLoads<String> loads=new BoundedPlanLoads<>(new SimpleMeterRegistry(),1,1);ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{Future<String> leader=pool.submit(()->loads.get("a",()->{entered.countDown();await(release);return "ok";},10000));await(entered);
            assertThrows(AttemptAdmissionException.class,()->loads.get("a",()->"bad",10));
            assertThrows(AttemptAdmissionException.class,()->loads.get("a",()->"bad",10));release.countDown();assertEquals("ok",leader.get(5,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
