package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Redis semantics of the V3 scripts. Opt-in: -Dattempt.gate.redis.port=... (DB 14, only {tkv3} keys). */
class V3RedisScriptTest {
    LettuceConnectionFactory factory;StringRedisTemplate redis;V3Redis v3;
    @BeforeEach void connect(){
        String port=System.getProperty("attempt.gate.redis.port");assumeTrue(port!=null,"set -Dattempt.gate.redis.port to run");
        RedisStandaloneConfiguration c=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(port));c.setDatabase(14);
        factory=new LettuceConnectionFactory(c);factory.afterPropertiesSet();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();clean();
        v3=new V3Redis(redis,new MockEnvironment());v3.ensureGroup();v3.mode("OPEN");
    }
    @AfterEach void close(){if(redis!=null)clean();if(factory!=null)factory.destroy();}
    void clean(){Set<String> keys=redis.keys("{tkv3}*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    static ReserveRequest request(String id,long user,String session){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId(user);r.setEventSessionId(session);r.setTicketSkuId("sku");r.setQuantity(1);r.setDeviceId("dev");return r;}
    List<String> accept(String id,long user,String session,long waitMs,int cap){return v3.accept(request(id,user,session),"fp-"+user,"att-"+id,"res-"+id,"ord-"+id,"ip",1,waitMs,4,cap,3000,"");}
    List<String> accept(String id,long user){return accept(id,user,"s",5000,100);}
    V3Redis.Message only(){List<MapRecord<String,Object,Object>> r=v3.readNew("c1",10);assertEquals(1,r.size());return new V3Redis.Message(r.get(0).getId().getValue(),r.get(0).getValue());}
    String start(V3Redis.Message m,String token,long lease){return v3.start(m,token,lease,16).get(0);}
    long pending(){return v3.pendingCount();}

    @Test void closedModeAcceptsNothing(){v3.mode("CLOSED");assertEquals("CLOSED",accept("a",1).get(0));assertEquals(0,v3.streamLength());assertTrue(v3.request("a").isEmpty());}
    @Test void acceptIsIdempotentAndCarriesTheWholeMessage(){
        List<String> first=accept("a",1);assertEquals("ACCEPTED",first.get(0));assertEquals("att-a",first.get(1));
        List<String> again=accept("a",1);assertEquals("KNOWN",again.get(0));assertEquals("att-a",again.get(1));assertEquals("WAITING",again.get(2));
        assertEquals("MISMATCH",v3.accept(request("a",1,"s"),"other-fp","x","x","x","ip",1,5000,4,100,3000,"").get(0));
        V3Redis.Message m=only();
        assertEquals("a",m.requestId);assertEquals("att-a",m.attemptId);assertEquals("res-a",m.reservationId);assertEquals("ord-a",m.orderId);assertEquals("1",m.userId);
        assertEquals("s",m.sessionId);assertEquals(1,m.quantity);assertEquals(1,m.saleVersion);assertEquals(Long.parseLong(first.get(3)),m.deadline);assertEquals(5000,m.deadline-m.acceptedAt);
    }
    @Test void sameUserSessionIsAliasedWhileInFlightAndFreedAfterSettlement(){
        assertEquals("ACCEPTED",accept("a",1).get(0));
        List<String> alias=accept("b",1);assertEquals("ALIAS",alias.get(0));assertEquals("a",alias.get(1));
        assertEquals("ACCEPTED",accept("c",1,"other-session",5000,100).get(0),"other session is independent");
        MapRecord<String,Object,Object> first=v3.readNew("c1",10).stream().filter(x->"a".equals(x.getValue().get("requestId"))).findFirst().get();
        V3Redis.Message m=new V3Redis.Message(first.getId().getValue(),first.getValue());
        assertEquals("START",start(m,"t1",15000));
        assertTrue(v3.settle("a","1","s","DONE","RESERVED",m.streamId,"t1",null));
        assertEquals("ACCEPTED",accept("d",1).get(0),"owner released after settlement");
    }
    @Test void busyAndUserLimitWriteNothing(){
        assertEquals("ACCEPTED",accept("a",1,"s1",5000,2).get(0));assertEquals("ACCEPTED",accept("b",2,"s1",5000,2).get(0));
        assertEquals("BUSY",accept("c",3,"s1",5000,2).get(0));
        assertTrue(v3.request("c").isEmpty());assertEquals(2,v3.streamLength());assertFalse(redis.hasKey(V3Redis.owner("3","s1")));
        for(int i=0;i<4;i++)assertEquals("ACCEPTED",accept("u"+i,9,"s"+i,5000,100).get(0));
        assertEquals("USER_LIMIT",accept("u4",9,"s4",5000,100).get(0));assertTrue(v3.request("u4").isEmpty());
    }
    @Test void startLeaseTakeoverAndStaleTokenCannotSettle()throws Exception{
        accept("a",1);V3Redis.Message m=only();
        assertEquals("START",start(m,"t1",300));
        assertEquals("HELD_BY_OTHER",start(m,"t2",300),"live lease");
        Thread.sleep(400);
        assertEquals("TAKEOVER",start(m,"t2",15000));
        assertFalse(v3.settle("a","1","s","DONE","RESERVED",m.streamId,"t1",null),"previous holder cannot settle or ACK");
        assertEquals(1,pending());assertEquals(1,v3.streamLength());
        assertTrue(v3.settle("a","1","s","DONE","RESERVED",m.streamId,"t2",null));
        assertEquals(0,pending());assertEquals(0,v3.streamLength(),"XACK and XDEL of the settled id only");
        assertEquals("DONE",v3.request("a").get("state"));assertEquals("RESERVED",v3.request("a").get("code"));
        assertEquals(0,redis.opsForZSet().zCard(V3Redis.ACTIVE));assertFalse(redis.hasKey(V3Redis.owner("1","s")));
        assertEquals("1",redis.opsForHash().get(V3Redis.CFG,"done"));
        assertEquals("SETTLED",start(m,"t3",15000),"a redelivered settled message only acknowledges");
    }
    @Test void timeoutIsDecidedOnceAndNotAcknowledgedBeforeTheFence()throws Exception{
        accept("late",1,"s",150,100);V3Redis.Message m=only();Thread.sleep(250);
        assertEquals("TIMEOUT",start(m,"t1",15000));assertEquals(1,pending(),"no ACK before the MySQL fence");
        assertEquals("TIMEOUT",start(m,"t2",15000),"still a timeout on retry, never a start");
        assertEquals(0,redis.opsForZSet().zCard(V3Redis.WAITING));
        assertTrue(v3.settle("late","1","s","DONE","QUEUE_TIMEOUT",m.streamId,"",null));assertEquals(0,pending());
    }
    @Test void activeLimitKeepsWorkWaitingInThePel(){
        redis.opsForHash().put(V3Redis.CFG,"activeLimit","1");
        accept("a",1);accept("b",2);List<MapRecord<String,Object,Object>> r=v3.readNew("c1",10);assertEquals(2,r.size());
        V3Redis.Message a=new V3Redis.Message(r.get(0).getId().getValue(),r.get(0).getValue()),b=new V3Redis.Message(r.get(1).getId().getValue(),r.get(1).getValue());
        assertEquals("START",start(a,"ta",15000));assertEquals("FULL",start(b,"tb",15000));assertEquals(2,pending());
        assertEquals(2,v3.readOwnPending("c1",10).size(),"both still owned by c1");
        assertTrue(v3.settle("a","1","s","DONE","RESERVED",a.streamId,"ta",null));
        assertEquals("START",start(b,"tb",15000));
    }
    @Test void lostRequestStateIsRebuiltFromTheMessage()throws Exception{
        accept("a",1);V3Redis.Message m=only();redis.delete(V3Redis.req("a"));
        assertEquals("START",start(m,"t",15000));assertEquals("1",v3.request("a").get("rebuilt"));
        accept("b",2,"s",100,100);List<MapRecord<String,Object,Object>> r=v3.readNew("c1",1);V3Redis.Message late=new V3Redis.Message(r.get(0).getId().getValue(),r.get(0).getValue());
        redis.delete(V3Redis.req("b"));Thread.sleep(200);assertEquals("TIMEOUT",start(late,"t",15000));
    }
    @Test void idleMessagesMoveToAnotherConsumerAndTouchKeepsLiveOnesHome()throws Exception{
        accept("a",1);accept("b",2);List<MapRecord<String,Object,Object>> r=v3.readNew("c1",10);assertEquals(2,r.size());
        Thread.sleep(300);
        v3.touch("c1",Collections.singletonList(r.get(0).getId().getValue()));
        List<MapRecord<String,Object,Object>> claimed=v3.claimIdle("c2",Duration.ofMillis(250),10);
        assertEquals(1,claimed.size());assertEquals(r.get(1).getId(),claimed.get(0).getId());assertEquals("b",claimed.get(0).getValue().get("requestId"));
    }
    @Test void capacityPublishTrimsStaleActiveAndReportsCounters(){
        redis.opsForZSet().add(V3Redis.ACTIVE,"stale",1);
        long[] seen=v3.publishCapacity(7,3,30000);
        assertEquals(0,seen[1]);assertEquals("7",redis.opsForHash().get(V3Redis.CFG,"cap"));assertEquals("3",redis.opsForHash().get(V3Redis.CFG,"activeLimit"));
        for(int i=0;i<7;i++)assertEquals("ACCEPTED",accept("x"+i,100+i,"s",5000,1).get(0),"fresh published cap overrides the fallback");
        assertEquals("BUSY",accept("x7",200,"s",5000,1).get(0));
    }
}
