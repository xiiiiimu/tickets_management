package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.sale.SaleManifest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Redis script contract simulation; the actual scripts are exercised against Redis in L2. */
class ReviewQuickCheckTest {
    StringRedisTemplate redis;ValueOperations<String,String> values;Db db;JdbcTemplate jdbc;ReviewQuickCheck quick;ObjectMapper json=new ObjectMapper();
    Map<String,String> cache=new HashMap<>();Map<String,Map<String,Long>> windows=new HashMap<>();AtomicLong now=new AtomicLong(1800000000000L);AtomicInteger conflicts=new AtomicInteger();SimpleMeterRegistry metrics;
    Map<String,Object> job;SaleManifest plan;AttemptRiskProfiles.Snapshot profile;
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);jdbc=mock(JdbcTemplate.class);db=new Db(jdbc,json);metrics=new SimpleMeterRegistry();quick=new ReviewQuickCheck(db,redis,json,metrics,new MockEnvironment());quick.clock(now::get);
        when(values.get(anyString())).thenAnswer(a->cache.get(a.getArgument(0)));
        when(values.setIfAbsent(anyString(),anyString(),any(java.time.Duration.class))).thenAnswer(a->{String key=a.getArgument(0);if(cache.containsKey(key))return false;cache.put(key,a.getArgument(1));return true;});
        when(redis.hasKey(anyString())).thenAnswer(a->cache.containsKey(a.getArgument(0)));when(redis.countExistingKeys(anyCollection())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(),any(),any())).thenAnswer(a->conflicts.get());
        when(redis.execute(eq(ReviewQuickCheck.WINDOW),anyList(),anyString(),anyString(),eq("ONCE"))).thenAnswer(a->{String key=((List<String>)a.getArgument(1)).get(0);long period=Long.parseLong(a.getArgument(2));Map<String,Long> w=windows.computeIfAbsent(key,k->new HashMap<>());w.values().removeIf(t->t<=now.get()-period);w.putIfAbsent(a.getArgument(3),now.get());return (long)w.size();});
        when(redis.execute(eq(ReviewQuickCheck.PUBLISH),anyList(),anyString(),anyString(),anyString(),anyString())).thenAnswer(a->{List<String> keys=a.getArgument(1);if(!Objects.equals(cache.get(keys.get(0)),a.getArgument(2)))return 0L;long ttl=Math.min(Long.parseLong(a.getArgument(4)),Long.parseLong(a.getArgument(5))-now.get());if(ttl<=0)return -1L;Map<String,Object> value=json.readValue((String)a.getArgument(3),Map.class);value.put("createdAt",now.get());value.put("expiresAt",now.get()+ttl);cache.put(keys.get(1),json.writeValueAsString(value));cache.remove(keys.get(0));return 1L;});
        when(redis.execute(eq(ReviewQuickCheck.UNLOCK),anyList(),anyString())).thenAnswer(a->{String key=((List<String>)a.getArgument(1)).get(0);if(Objects.equals(cache.get(key),a.getArgument(2))){cache.remove(key);return 1L;}return 0L;});
        job=new LinkedHashMap<>();job.put("id","attempt-1");job.put("request_id","r1");job.put("user_id",7L);job.put("session_id","session");job.put("sku_id","sku");job.put("device_id","dev");job.put("ip","ip");
        plan=new SaleManifest("{\"version\":1,\"sessionId\":\"session\",\"skus\":[{\"id\":\"sku\",\"saleEnd\":"+(now.get()+3600000)+"}],\"rules\":[]}",json);profile=new AttemptRiskProfiles.Snapshot("REVIEW","profiles-v1",now.get()+3600000,50);
    }
    @Test void firstAgreeThenNewAttemptHitsCacheAndSkipsExtraSql(){assertEquals("AGREE",quick.review(job,plan,profile).decision);clearInvocations(jdbc);job.put("id","attempt-2");ReviewQuickCheck.Result second=quick.review(job,plan,profile);assertEquals("AGREE",second.decision);assertTrue(second.cached);verifyNoInteractions(jdbc);}
    @Test void expiredAgreeRunsReviewAgainWithoutSleeping(){quick.review(job,plan,profile);now.addAndGet(60001);assertFalse(quick.review(job,plan,profile).cached);verify(jdbc,times(2)).queryForObject(anyString(),eq(Integer.class),any(),any(),any());}
    @Test void rejectIsIdempotentForSameAttemptButNewLegalAttemptRechecks(){conflicts.set(1);assertEquals("REJECT",quick.review(job,plan,profile).decision);conflicts.set(0);assertEquals("REJECT",quick.review(job,plan,profile).decision);job.put("id","attempt-2");assertEquals("AGREE",quick.review(job,plan,profile).decision);}
    @Test void profileOrRulesOrSecurityContextChangeInvalidatesAgree(){quick.review(job,plan,profile);assertFalse(quick.review(job,plan,new AttemptRiskProfiles.Snapshot("REVIEW","profiles-v2",profile.validUntil,50)).cached);SaleManifest changed=new SaleManifest(plan.json().replace("\"version\":1","\"version\":2"),json);assertFalse(quick.review(job,changed,profile).cached);job.put("device_id","other-device");assertFalse(quick.review(job,plan,profile).cached);}
    @Test void lockContentionFailsClosedWithoutWaitingOrSql(){cache.put(quick.cacheKey(job)+":lock","other-owner");assertEquals("REVIEW_BUSY",assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,profile)).code);verifyNoInteractions(jdbc);assertEquals("other-owner",cache.get(quick.cacheKey(job)+":lock"));}
    @Test void staleOwnerCannotPublishOrDeleteReplacementLock(){when(redis.execute(eq(ReviewQuickCheck.PUBLISH),anyList(),anyString(),anyString(),anyString(),anyString())).thenAnswer(a->{cache.put(quick.cacheKey(job)+":lock","new-owner");return 0L;});assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,profile));assertEquals("new-owner",cache.get(quick.cacheKey(job)+":lock"));assertFalse(cache.containsKey(quick.cacheKey(job)));}
    @Test void expiredContextIsRetryableValidationFailureNotOldProcessingDeadline(){AttemptRiskProfiles.Snapshot expired=new AttemptRiskProfiles.Snapshot("REVIEW","old",now.get()-1,0);assertEquals("REVIEW_CONTEXT_UNAVAILABLE",assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,expired)).code);assertFalse(cache.containsKey(quick.cacheKey(job)));}
    @Test void cacheFailureOrSqlFailureDoesNotDefaultAgree(){when(values.get(anyString())).thenThrow(new IllegalStateException("cache down"));assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,profile));}
    @Test void currentFrequencyCountsUniqueAttemptsAndRejectIsNotPermanent(){for(int i=1;i<=3;i++){job.put("id","a"+i);cache.remove(quick.cacheKey(job));assertEquals("AGREE",quick.review(job,plan,profile).decision);}job.put("id","a4");cache.remove(quick.cacheKey(job));assertEquals("QUICK_FREQUENCY",quick.review(job,plan,profile).reason);now.addAndGet(2001);job.put("id","a5");assertEquals("AGREE",quick.review(job,plan,profile).decision);}
    @Test void agreeExpiryCannotOutliveFormalProfile()throws Exception{quick.review(job,plan,new AttemptRiskProfiles.Snapshot("REVIEW","p",now.get()+1234,0));Map<?,?> value=json.readValue(cache.get(quick.cacheKey(job)),Map.class);assertEquals(now.get()+1234,((Number)value.get("expiresAt")).longValue());}
    @Test void missingDeviceFlagOrConcurrentQueryFailureIsUnavailable(){when(redis.hasKey(anyString())).thenReturn(null);assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,profile));}
    @Test void instantaneousDeviceFlagRejectsWithoutInventedHistory(){cache.put("risk:blacklist:device:dev","1");assertEquals("QUICK_DEVICE_FLAG",quick.review(job,plan,profile).reason);}
    @Test void concurrencyQueryUsesExistingAttemptUserIndexPrefixAndExcludesSelf(){quick.review(job,plan,profile);verify(jdbc).queryForObject(contains("WHERE attempt_protocol IN (3,4) AND user_id=? AND session_id=? AND id<>?"),eq(Integer.class),eq(7L),eq("session"),eq("attempt-1"));}
    @Test void commonRateLimitAppliesIndependentlyOfAgreeCache(){quick.review(job,plan,profile);assertNull(quick.base(job,2));assertEquals("RATE_LIMITED",quick.base(job,1));verify(redis).countExistingKeys(anyCollection());}
    @Test void fractionalCommonRateThresholdKeepsExistingComparisonSemantics(){assertNull(quick.base(job,1.5));}
    @Test void concurrentAttemptSqlFailureIsUnavailableAndReleasesOwnLock(){when(jdbc.queryForObject(anyString(),eq(Integer.class),any(),any(),any())).thenThrow(new IllegalStateException("DB unavailable"));assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(job,plan,profile));assertFalse(cache.containsKey(quick.cacheKey(job)+":lock"));assertFalse(cache.containsKey(quick.cacheKey(job)));}
    @Test void concurrentMissDoesNotDuplicateReviewOrWaitForOtherWorker()throws Exception{
        java.util.concurrent.CountDownLatch checking=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(),any(),any())).thenAnswer(a->{checking.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return 0;});
        try{java.util.concurrent.Future<ReviewQuickCheck.Result> first=pool.submit(()->quick.review(job,plan,profile));assertTrue(checking.await(3,java.util.concurrent.TimeUnit.SECONDS));Map<String,Object> other=new HashMap<>(job);other.put("id","attempt-2");assertEquals("REVIEW_BUSY",assertThrows(AttemptRiskAdmission.Unavailable.class,()->quick.review(other,plan,profile)).code);release.countDown();assertEquals("AGREE",first.get(3,java.util.concurrent.TimeUnit.SECONDS).decision);assertTrue(quick.review(other,plan,profile).cached);verify(jdbc,times(1)).queryForObject(anyString(),eq(Integer.class),any(),any(),any());}
        finally{release.countDown();pool.shutdownNow();}
    }
}
