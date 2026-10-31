package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.sale.SaleManifest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.*;

/** Short deterministic review, isolated from the long-term portrait and historical features. */
@Service
public class ReviewQuickCheck {
    static final DefaultRedisScript<Long> WINDOW=new DefaultRedisScript<>();
    static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>(
        "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end; local t=redis.call('TIME');local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000);"
        +"local ttl=math.min(tonumber(ARGV[3]),tonumber(ARGV[4])-now);if ttl<=0 then redis.call('DEL',KEYS[1]);return -1 end;"
        +"local v=cjson.decode(ARGV[2]);v.createdAt=now;v.expiresAt=now+ttl;redis.call('SET',KEYS[2],cjson.encode(v),'PX',ttl);redis.call('DEL',KEYS[1]);return 1",Long.class);
    static final DefaultRedisScript<Long> UNLOCK=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class);
    static {WINDOW.setLocation(new ClassPathResource("lua/risk_sliding_window.lua"));WINDOW.setResultType(Long.class);}
    public static final class Result {
        public final String decision,reason;public final boolean cached;
        Result(String decision,String reason,boolean cached){this.decision=decision;this.reason=reason;this.cached=cached;}
    }
    private final Db db;private final StringRedisTemplate redis;private final ObjectMapper json;private final MeterRegistry metrics;
    private final long ttl,window,lockMillis;private final int maximum;
    private java.util.function.LongSupplier clock=System::currentTimeMillis;
    void clock(java.util.function.LongSupplier clock){this.clock=clock;}
    public ReviewQuickCheck(Db db,StringRedisTemplate redis,ObjectMapper json,MeterRegistry metrics,Environment env){
        this.db=db;this.redis=redis;this.json=json;this.metrics=metrics;
        ttl=env.getProperty("ticketing.reservations-v2.attempts.review-cache-ms",Long.class,60000L);
        window=env.getProperty("ticketing.reservations-v2.attempts.review-window-ms",Long.class,2000L);
        maximum=env.getProperty("ticketing.reservations-v2.attempts.review-max-attempts",Integer.class,3);
        lockMillis=3000;
        if(ttl<1||ttl>300000||window<1||window>10000||maximum<1||maximum>100)throw new IllegalArgumentException("Invalid quick review policy");
    }
    static String text(Map<String,Object> job,String key){return String.valueOf(job.get(key));}
    public String cacheKey(Map<String,Object> job){return "risk:review:{"+AttemptRiskProfiles.digest(text(job,"session_id"))+"}:"+AttemptRiskProfiles.digest(text(job,"user_id")+"|"+text(job,"device_id")+"|"+text(job,"ip"));}
    String ruleVersion(SaleManifest plan){return AttemptRiskProfiles.digest("quick-review-v1|"+window+"|"+maximum+"|"+ttl+"|"+plan.digest());}
    /** Existing common rate limit and explicit live blacklists remain mandatory for PASS and REVIEW. */
    public String base(Map<String,Object> job,double rateLimit){
        Long count=redis.execute(WINDOW,Collections.singletonList("risk:ip:"+text(job,"ip")),"60000","reservation:"+text(job,"request_id"),"ONCE");
        if(count==null)throw new AttemptRiskAdmission.Unavailable("RISK_SIGNAL_UNAVAILABLE",null);
        if(count>=rateLimit)return "RATE_LIMITED";
        Long blocked=redis.countExistingKeys(Arrays.asList("risk:blacklist:user:"+text(job,"user_id"),"risk:blacklist:device:"+text(job,"device_id"),"risk:blacklist:ip:"+text(job,"ip")));
        if(blocked==null)throw new AttemptRiskAdmission.Unavailable("RISK_SIGNAL_UNAVAILABLE",null);
        return blocked>0?"BLACKLIST":null;
    }
    public Result review(Map<String,Object> job,SaleManifest plan,AttemptRiskProfiles.Snapshot profile){
        String key=cacheKey(job),lock=key+":lock",owner=Db.id(),rules=ruleVersion(plan);
        String context=AttemptRiskProfiles.digest(key+"|"+plan.version());
        try{
            Result hit=cached(key,job,profile,rules,context,plan);if(hit!=null)return hit;
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,owner,java.time.Duration.ofMillis(lockMillis))))throw new AttemptRiskAdmission.Unavailable("REVIEW_BUSY",null);
            try{
                hit=cached(key,job,profile,rules,context,plan);if(hit!=null)return hit;
                long start=System.nanoTime();String reason;
                try{reason=check(job);}finally{metrics.timer("attempt.review.execution").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
                Result result=new Result(reason==null?"AGREE":"REJECT",reason==null?"QUICK_AGREE":reason,false);
                Map<String,Object> value=new LinkedHashMap<>();value.put("decision",result.decision);value.put("reason",result.reason);value.put("attemptId",job.get("id"));
                value.put("profileVersion",profile.version);value.put("ruleVersion",rules);value.put("context",context);
                Map<String,Object> sku=plan.sku(text(job,"sku_id"));if(sku==null||!(sku.get("saleEnd") instanceof Number))throw new IllegalStateException("Missing sale deadline");
                long end=Math.min(profile.validUntil,((Number)sku.get("saleEnd")).longValue());
                Long published=redis.execute(PUBLISH,Arrays.asList(lock,key),owner,json.writeValueAsString(value),Long.toString(ttl),Long.toString(end));
                if(!Long.valueOf(1).equals(published))throw new AttemptRiskAdmission.Unavailable(Long.valueOf(0).equals(published)?"REVIEW_BUSY":"REVIEW_CONTEXT_UNAVAILABLE",null);
                metrics.counter("attempt.review.cache","outcome","miss").increment();return result;
            }finally{try{redis.execute(UNLOCK,Collections.singletonList(lock),owner);}catch(RuntimeException ignored){/* bounded owner TTL; never convert failure into AGREE */}}
        }catch(AttemptRiskAdmission.Unavailable failure){throw failure;}
        catch(Exception failure){throw new AttemptRiskAdmission.Unavailable("REVIEW_UNAVAILABLE",failure);}
    }
    private Result cached(String key,Map<String,Object> job,AttemptRiskProfiles.Snapshot profile,String rules,String context,SaleManifest plan)throws Exception{
        String raw=redis.opsForValue().get(key);if(raw==null)return null;Map<?,?> value=json.readValue(raw,Map.class);
        Object decision=value.get("decision");if(!Arrays.asList("AGREE","REJECT").contains(decision)||!(value.get("createdAt") instanceof Number)||!(value.get("expiresAt") instanceof Number))throw new IllegalStateException("Malformed quick review result");
        long now=clock.getAsLong(),created=((Number)value.get("createdAt")).longValue(),expires=((Number)value.get("expiresAt")).longValue();
        Map<String,Object> sku=plan.sku(text(job,"sku_id"));
        if(created>now+1000||expires<=created||expires-created>ttl)throw new IllegalStateException("Invalid quick review timestamps");
        if(expires<=now||sku==null||!(sku.get("saleEnd") instanceof Number)||expires>Math.min(profile.validUntil,((Number)sku.get("saleEnd")).longValue()))return null;
        if(!Objects.equals(profile.version,value.get("profileVersion"))||!Objects.equals(rules,value.get("ruleVersion"))||!Objects.equals(context,value.get("context")))return null;
        if("REJECT".equals(decision)&&!Objects.equals(job.get("id"),value.get("attemptId")))return null;
        metrics.counter("attempt.review.cache","outcome","hit").increment();return new Result(decision.toString(),String.valueOf(value.get("reason")),true);
    }
    private String check(Map<String,Object> job){
        Long n=redis.execute(WINDOW,Collections.singletonList("risk:review-frequency:"+AttemptRiskProfiles.digest(text(job,"user_id")+"|"+text(job,"session_id"))),Long.toString(window),"attempt:"+text(job,"id"),"ONCE");
        if(n==null)throw new IllegalStateException("Quick frequency unavailable");if(n>maximum)return "QUICK_FREQUENCY";
        Integer conflicts=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE attempt_protocol IN (3,4) AND user_id=? AND session_id=? AND id<>? AND state IN ('WAITING','PROCESSING')",Integer.class,job.get("user_id"),job.get("session_id"),job.get("id"));
        if(conflicts==null)throw new IllegalStateException("Unconfirmed concurrent attempt check");if(conflicts>0)return "QUICK_ATTEMPT_CONFLICT";
        Boolean blocked=redis.hasKey("risk:blacklist:device:"+text(job,"device_id"));if(blocked==null)throw new IllegalStateException("Device flag unavailable");return blocked?"QUICK_DEVICE_FLAG":null;
    }
}
