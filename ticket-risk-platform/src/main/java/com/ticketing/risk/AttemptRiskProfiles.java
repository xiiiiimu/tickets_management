package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.repository.RiskProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;

/** Strict attempt reader; never changes the legacy profile reader or the portrait writers. */
@Service
public class AttemptRiskProfiles {
    public static final class Snapshot {
        public final String decision,version;public final long validUntil;public final int score;
        Snapshot(String decision,String version,long validUntil,int score){this.decision=decision;this.version=version;this.validUntil=validUntil;this.score=score;}
    }
    private final RiskProfileRepository profiles;private final StringRedisTemplate redis;private final ObjectMapper json;
    private LongSupplier clock=System::currentTimeMillis;
    public AttemptRiskProfiles(RiskProfileRepository profiles,StringRedisTemplate redis,ObjectMapper json){this.profiles=profiles;this.redis=redis;this.json=json;}
    void clock(LongSupplier clock){this.clock=clock;}
    public Snapshot read(long user,String device,String ip){
        try{
            Map<String,String> subjects=new LinkedHashMap<>();subjects.put("USER",Long.toString(user));
            if(device!=null&&!device.isEmpty())subjects.put("DEVICE",device);if(ip!=null&&!ip.isEmpty())subjects.put("IP",ip);
            // A transport/parse failure is not a cache miss. Cache hits are hints, not BLOCK authority.
            for(Map.Entry<String,String> s:subjects.entrySet()){
                String raw=redis.opsForValue().get(RiskProfileCache.key(s.getKey(),s.getValue()));
                if(raw!=null){Map<?,?> cached=json.readValue(raw,Map.class);
                    if(!Arrays.asList("PASS","REVIEW","BLOCK").contains(cached.get("decision"))||!(cached.get("version") instanceof Number))throw new IllegalStateException("Malformed profile cache");}
            }
            List<RiskProfile> rows=profiles.findSubjects(user,device,ip);if(rows==null)throw new IllegalStateException("Unconfirmed portrait query");
            Map<String,RiskProfile> found=new HashMap<>();long now=clock.getAsLong(),until=Long.MAX_VALUE;int score=Integer.MIN_VALUE;boolean expired=false,blocked=false,review=false;
            for(RiskProfile p:rows){
                if(p==null)continue;
                if(!Objects.equals(subjects.get(p.getSubjectType()),p.getSubjectValue())||found.put(p.getSubjectType(),p)!=null
                    ||!Arrays.asList("PASS","REVIEW","BLOCK").contains(p.getDecision())||p.getVersion()<0)throw new IllegalStateException("Invalid formal portrait");
                long end=p.getExpiresAt()==null?Long.MAX_VALUE:p.getExpiresAt().getTime();
                if(end<=now){expired=true;continue;}
                until=Math.min(until,end);score=Math.max(score,p.getScore());blocked|="BLOCK".equals(p.getDecision());review|="REVIEW".equals(p.getDecision());
            }
            List<Object> vector=new ArrayList<>();
            for(Map.Entry<String,String> s:subjects.entrySet()){
                RiskProfile p=found.get(s.getKey());vector.add(Arrays.asList(s.getKey(),s.getValue(),p==null?"MISSING":Arrays.asList(p.getId(),p.getVersion(),p.getDecision(),p.getExpiresAt()==null?null:p.getExpiresAt().getTime())));
            }
            if(!blocked&&expired)throw new IllegalStateException("Expired portrait is not a new user");
            return new Snapshot(blocked?"BLOCK":review||!found.containsKey("USER")?"REVIEW":"PASS",digest(json.writeValueAsString(vector)),until,score==Integer.MIN_VALUE?0:score);
        }catch(AttemptRiskAdmission.Unavailable failure){throw failure;}
        catch(Exception failure){throw new AttemptRiskAdmission.Unavailable("PROFILE_UNAVAILABLE",failure);}
    }
    static String digest(String value){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
