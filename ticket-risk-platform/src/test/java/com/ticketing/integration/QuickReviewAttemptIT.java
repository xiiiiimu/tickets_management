package com.ticketing.integration;

import com.ticketing.infrastructure.Db;
import com.ticketing.risk.*;
import com.ticketing.ticket.*;
import com.ticketing.inventory.InventoryService;
import com.ticketing.repository.RiskProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly selected L2 methods only. All rows and keys belong to the disposable owner fixture. */
@TestPropertySource(properties={"ticketing.reservations-v2.attempts.ordered-lua=true","ticketing.reservations-v2.attempts.quick-review=true"})
class QuickReviewAttemptIT extends DurableAttemptLifecycleIT {
    @org.springframework.beans.factory.annotation.Autowired AttemptRiskAdmission admission;
    @org.springframework.beans.factory.annotation.Autowired ReviewQuickCheck quick;
    @org.springframework.beans.factory.annotation.Autowired RiskProfileRepository profiles;
    ObjectMapper json=new ObjectMapper();
    ReserveRequest next(ReserveRequest old){ReserveRequest r=new ReserveRequest();r.setRequestId(Db.id());r.setUserId(old.getUserId());r.setEventSessionId(old.getEventSessionId());r.setTicketSkuId(old.getTicketSkuId());r.setQuantity(old.getQuantity());r.setDeviceId(old.getDeviceId());return r;}
    Map<String,Object> run(ReserveRequest r){reliable(r);Map<String,Object> claimed=attempts.claim();assertNotNull(claimed);assertEquals(r.getRequestId(),claimed.get("request_id"));ReflectionTestUtils.invokeMethod(service,"process",claimed);return queue.byRequest(r.getRequestId());}
    Map<?,?> decision(Map<String,Object> job)throws Exception{return json.readValue(job.get("admission").toString(),Map.class);}
    void quota(ReserveRequest r,int quantity){db.jdbc.update("INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(?,?,?) ON DUPLICATE KEY UPDATE quantity=?",r.getUserId(),r.getTicketSkuId(),quantity,quantity);}
    void block(ReserveRequest r){new TransactionTemplate(manager).executeWithoutResult(s->{RiskProfile p=new RiskProfile();p.setSubjectType("USER");p.setSubjectValue(r.getUserId().toString());p.setDecision("BLOCK");p.setRiskLevel("HIGH");p.setScore(100);p.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));assertTrue(profiles.upsert(p));});}
    @Test void agreeCacheDoesNotBypassMysqlLimitAndLegalRetryCanHold()throws Exception{
        isolated();Sale s=sale("PASS");ReserveRequest r=request(s);quota(r,2);
        Map<String,Object> first=run(r);assertEquals("FAILED",first.get("state"));assertEquals("PURCHASE_LIMIT",first.get("result_code"));assertEquals("AGREE",decision(first).get("quickDecision"));assertEquals(false,decision(first).get("reviewCacheHit"));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        Map<String,Object> second=run(next(r));assertEquals("FAILED",second.get("state"));assertEquals(true,decision(second).get("reviewCacheHit"));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        quota(r,0);ReserveRequest legal=next(r);Map<String,Object> held=run(legal);assertEquals("RESERVED",held.get("state"));assertEquals("REVIEW",decision(held).get("decision"));assertEquals(true,decision(held).get("reviewCacheHit"));
        assertEquals(99,stock(s));assertEquals(99,redisStock(s));assertEquals(held.get("id"),service.registerAttempt(legal,"127.0.0.1").get("attemptId"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_profile WHERE subject_type='USER' AND subject_value=?",Integer.class,r.getUserId().toString()));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_risk_decision WHERE order_id=? AND phase='ADMISSION' AND decision='REVIEW'",Integer.class,held.get("order_id")));
        verifyNoInteractions(agent);
    }
    @Test void latestBlockWinsOverCachedAgreeAndPostQualificationBlockIsFenced()throws Exception{
        isolated();Sale s=sale("PASS");ReserveRequest r=request(s);quota(r,2);Map<String,Object> initial=run(r);assertEquals("AGREE",decision(initial).get("quickDecision"));quota(r,0);
        ReserveRequest racing=next(r);reliable(racing);Map<String,Object> claimed=attempts.claim();Map<String,Object> d=admission.classify(claimed,armory.runtimePlan(s.session,s.sku));assertEquals("AGREE",d.get("quickDecision"));assertNotNull(attempts.decided(claimed,d));
        block(r);ReflectionTestUtils.invokeMethod(service,"process",claimed);assertEquals("REJECTED",queue.byRequest(racing.getRequestId()).get("state"));assertEquals(100,stock(s));assertEquals(100,redisStock(s));
        redis.opsForValue().set(RiskProfileCache.key("USER",r.getUserId().toString()),"{\"decision\":\"PASS\",\"version\":0}");
        Map<String,Object> rejected=run(next(r));assertEquals("REJECTED",rejected.get("state"));assertEquals("BLOCK",decision(rejected).get("profileDecision"));assertFalse(decision(rejected).containsKey("quickDecision"));assertEquals(100,redisStock(s));
        verify(inventory,times(1)).reserveRuntime(any(),anyLong(),anyLong());verifyNoInteractions(agent);
    }
    @Test void rejectFromConcurrentAttemptDoesNotPoisonNextLegalAttempt()throws Exception{
        isolated();Sale s=sale("PASS");ReserveRequest r=request(s);reliable(r);Map<String,Object> claimed=attempts.claim();String conflict=Db.id();
        // A deliberately conflicting recovery fixture; no historical order or fabricated device signal.
        db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,reservation_id,order_id,attempt_protocol,waiting_deadline) VALUES(?,?,?,?,?,?,1,?,?,1,'WAITING',?,?,?,3,?)",conflict,Db.id(),"conflict",r.getUserId(),s.session,s.sku,r.getDeviceId(),"127.0.0.1",new Timestamp(System.currentTimeMillis()+5000),Db.id(),Db.id(),new Timestamp(System.currentTimeMillis()+5000));
        ReflectionTestUtils.invokeMethod(service,"process",claimed);Map<String,Object> denied=queue.byRequest(r.getRequestId());assertEquals("REJECTED",denied.get("state"));assertEquals("REJECT",decision(denied).get("quickDecision"));assertEquals("REVIEW",decision(denied).get("profileDecision"));assertEquals(100,redisStock(s));
        db.jdbc.update("UPDATE tb_reservation_job SET state='QUEUE_TIMEOUT',result_code='QUEUE_TIMEOUT' WHERE id=?",conflict);
        Map<String,Object> accepted=run(next(r));assertEquals("RESERVED",accepted.get("state"));assertEquals("AGREE",decision(accepted).get("quickDecision"));assertEquals(false,decision(accepted).get("reviewCacheHit"));assertEquals(99,stock(s));assertEquals(99,redisStock(s));
    }
    @Test @SuppressWarnings("unchecked") void redisScriptsFenceOwnersAndBoundTtlWithoutChangingPortraits()throws Exception{
        isolated();String key="risk:review:{"+Db.id()+"}:fixture",lock=key+":lock";redis.opsForValue().set(lock,"new-owner",java.time.Duration.ofSeconds(3));
        DefaultRedisScript<Long> publish=(DefaultRedisScript<Long>)ReflectionTestUtils.getField(ReviewQuickCheck.class,"PUBLISH"),unlock=(DefaultRedisScript<Long>)ReflectionTestUtils.getField(ReviewQuickCheck.class,"UNLOCK");
        long end=System.currentTimeMillis()+10000;String value="{\"decision\":\"AGREE\",\"attemptId\":\"fixture\"}";
        assertEquals(0L,redis.execute(publish,Arrays.asList(lock,key),"old-owner",value,"60000",Long.toString(end)));assertEquals(0L,redis.execute(unlock,Collections.singletonList(lock),"old-owner"));assertEquals("new-owner",redis.opsForValue().get(lock));
        assertEquals(1L,redis.execute(publish,Arrays.asList(lock,key),"new-owner",value,"60000",Long.toString(end)));Map<?,?> stored=json.readValue(redis.opsForValue().get(key),Map.class);assertTrue(((Number)stored.get("expiresAt")).longValue()<=end);assertTrue(redis.getExpire(key,java.util.concurrent.TimeUnit.MILLISECONDS)<=10000);assertFalse(redis.hasKey(lock));
        assertEquals(0L,redis.execute(publish,Arrays.asList(lock,key),"old-owner",value,"60000",Long.toString(end)));redis.expireAt(key,new java.util.Date(System.currentTimeMillis()-1));assertFalse(redis.hasKey(key));
    }
}
