package com.ticketing.ticket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * Runs the real gate Lua against a local Redis. Opt-in: -Dattempt.gate.redis.port=6379 (uses DB 15 and only
 * the {attempt-gate} keys it creates). Skipped otherwise so the default unit suite needs no Redis.
 */
class AttemptAdmissionGateRedisTest {
    LettuceConnectionFactory factory;StringRedisTemplate redis;ReservationAttemptStore store=mock(ReservationAttemptStore.class);
    SimpleMeterRegistry metrics=new SimpleMeterRegistry();AttemptAdmissionGate gate;MockEnvironment env;
    @BeforeEach void connect(){
        String port=System.getProperty("attempt.gate.redis.port");assumeTrue(port!=null,"set -Dattempt.gate.redis.port to run");
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(port));config.setDatabase(15);
        factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        clean();
        env=new MockEnvironment();env.setProperty("ticketing.reservations-v2.attempts.redis-gate-reconcile-ms","5000");
        gate=newGate();
    }
    AttemptAdmissionGate newGate(){
        @SuppressWarnings("unchecked") ObjectProvider<ReservationAttemptStore> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(store);
        return new AttemptAdmissionGate(redis,provider,metrics,env); // start() not called: reconcile is driven explicitly
    }
    @AfterEach void close(){if(redis!=null)clean();if(factory!=null)factory.destroy();}
    void clean(){java.util.Set<String> keys=redis.keys("{attempt-gate}*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    double decisions(String outcome){io.micrometer.core.instrument.Counter c=metrics.find("attempt.gate.decision").tag("outcome",outcome).counter();return c==null?0:c.count();}
    long held(){Long n=redis.opsForZSet().zCard(AttemptAdmissionGate.HELD);return n==null?0:n;}
    long fresh(){Long n=redis.opsForZSet().zCard(AttemptAdmissionGate.FRESH);return n==null?0:n;}
    void publish(int waiting,int limit){when(store.admissionEstimate()).thenReturn(new int[]{waiting,limit});gate.reconcile();}
    boolean admitted(String id){return !gate.admit(id).refused();}

    @Test void withoutPublishedStateEverythingPassesThroughAndNothingIsMarked(){
        assertTrue(admitted("a"));assertTrue(admitted("b"));
        assertEquals(2.0,decisions("pass_through"));assertFalse(redis.hasKey(AttemptAdmissionGate.MARKER+"a"));assertEquals(0,held());
    }
    @Test void refusesBeyondLimitButKnownRequestIdsAlwaysPass(){
        publish(0,2);
        AttemptAdmissionGate.Hold r1=gate.admit("r1");assertTrue(r1.reserved());assertTrue(admitted("r2"));
        assertFalse(admitted("r3"),"limit 2 reached");
        AttemptAdmissionGate.Hold retry=gate.admit("r1");
        assertFalse(retry.refused(),"an admitted requestId retry must reach MySQL for its original receipt");assertFalse(retry.reserved(),"a retry takes no second slot");
        assertEquals(1.0,decisions("refused"));assertEquals(1.0,decisions("known"));assertEquals(2,held());
        Long ttl=redis.getExpire(AttemptAdmissionGate.MARKER+"r1");assertTrue(ttl!=null&&ttl>500,"marker kept for the configured window");
    }
    @Test void reconcileNeverWipesUncommittedReservations(){
        publish(0,3);
        assertTrue(admitted("a"));assertTrue(admitted("b"));assertTrue(admitted("c"));assertFalse(admitted("d"));
        publish(0,3); // MySQL does not see a,b,c yet (still queued / in a running batch)
        assertFalse(admitted("d"),"held admissions must survive a MySQL COUNT that cannot see them");
        assertEquals(3,held());
        Long ttl=redis.getExpire(AttemptAdmissionGate.STATE);assertTrue(ttl!=null&&ttl>0,"state must expire if nodes stop publishing");
    }
    @Test void releaseFreesExactlyItsOwnSlotIdempotentlyAndNeverAnotherRequests(){
        publish(0,2);
        AttemptAdmissionGate.Hold mine=gate.admit("same");AttemptAdmissionGate.Hold other=gate.admit("other");assertFalse(admitted("third"));
        mine.notRegistered();
        assertEquals(1,held());assertFalse(redis.hasKey(AttemptAdmissionGate.MARKER+"same"),"our marker is removed so a retry is admitted afresh");
        AttemptAdmissionGate.Hold again=gate.admit("same");assertTrue(again.reserved(),"same requestId re-admitted with a new token");
        // A stale release of the first admission (repeat, late duplicate) must not touch the new admission or others.
        gate.release(mine);gate.release(mine);mine.notRegistered();
        assertEquals(2,held());assertEquals(again.token,redis.opsForValue().get(AttemptAdmissionGate.MARKER+"same"));
        assertTrue(redis.opsForValue().get(AttemptAdmissionGate.MARKER+"other")!=null);
        assertFalse(admitted("third"),"no slot was freed by the stale release");
        AttemptAdmissionGate.Hold known=gate.admit("other");known.notRegistered(); // a known-pass hold owns nothing
        assertEquals(2,held());assertEquals(other.token,redis.opsForValue().get(AttemptAdmissionGate.MARKER+"other"));
    }
    @Test void commitMovesHeldToFreshUntilAReconcileThatProvablySawTheRow(){
        publish(0,2);
        AttemptAdmissionGate.Hold a=gate.admit("a");assertTrue(admitted("b"));
        // The commit lands while the reconciler is between reading Redis time and reading MySQL: its count misses the row.
        when(store.admissionEstimate()).thenAnswer(call->{a.committed(true);return new int[]{0,2};});gate.reconcile();
        assertEquals(1,held());assertEquals(1,fresh());assertFalse(admitted("c"),"the committed row is still counted");
        publish(1,2); // this count includes the row
        assertEquals(0,fresh());assertFalse(admitted("c"));
        a.committed(true);a.notRegistered();assertEquals(0,fresh(),"settles at most once");
    }
    @Test void committedWithoutNewWaitingRowDropsTheSlotButKeepsTheMarker(){
        publish(0,1);
        AttemptAdmissionGate.Hold alias=gate.admit("alias");alias.committed(false);
        assertEquals(0,held());assertEquals(0,fresh());assertNotNull(redis.opsForValue().get(AttemptAdmissionGate.MARKER+"alias"));
        assertTrue(gate.admit("alias").reserved()==false,"a retry is known and reaches MySQL for the existing receipt");
        assertTrue(admitted("next"));
    }
    @Test void unknownOutcomeIsHeldUntilTheHoldExpiresThenFreed()throws Exception{
        env.setProperty("ticketing.reservations-v2.attempts.batch-register","true");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-wait-ms","100");
        env.setProperty("ticketing.reservations-v2.attempts.batch-register-taken-wait-ms","100");
        env.setProperty("ticketing.reservations-v2.attempts.redis-gate-hold-ms","1300");
        gate=newGate();publish(0,1);
        gate.admit("unknown"); // never settled: commit outcome unknown
        assertFalse(admitted("next"));
        Thread.sleep(1450);
        assertTrue(admitted("next"),"an unsettled entry is freed only by its expiry");
    }
    @Test void holdMustOutliveTheCallerWait(){
        env.setProperty("ticketing.reservations-v2.attempts.batch-register","true");
        env.setProperty("ticketing.reservations-v2.attempts.redis-gate-hold-ms","5500"); // default wait 1000 + taken 4000
        assertThrows(IllegalArgumentException.class,this::newGate);
    }
    @Test void leavingWaitingFreesASlotAndNeverGoesNegative(){
        publish(1,1);assertFalse(admitted("y"));
        gate.leftWaiting();assertTrue(admitted("y"));
        gate.leftWaiting();gate.leftWaiting();gate.leftWaiting();
        assertEquals("0",redis.opsForHash().get(AttemptAdmissionGate.STATE,"waiting"));
    }
    @Test void stateLossDegradesToPassThrough(){
        publish(5,5);assertFalse(admitted("z"));
        redis.delete(AttemptAdmissionGate.STATE);
        assertTrue(admitted("z"));
    }
    @Test void redisFailureIsPassThrough(){
        factory.destroy();factory=null;
        AttemptAdmissionGate.Hold h=gate.admit("q");assertFalse(h.refused());assertEquals(1.0,decisions("redis_error"));
        h.notRegistered();h.committed(true); // no-ops, must not throw
        redis=null;
    }
}
