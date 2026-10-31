package com.ticketing.risk;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.sale.SaleManifest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Service
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled","attempts.ordered-lua","attempts.quick-review"},havingValue="true")
public class AttemptRiskAdmission {
    public static final class Unavailable extends BusinessException {
        public final String code;
        public Unavailable(String code,Throwable cause){super(503,"资格校验暂不可用，请稍后重新发起抢票");this.code=code;if(cause!=null)initCause(cause);}
    }
    private final AttemptRiskProfiles profiles;private final ReviewQuickCheck quick;private final MeterRegistry metrics;
    public AttemptRiskAdmission(AttemptRiskProfiles profiles,ReviewQuickCheck quick,MeterRegistry metrics){this.profiles=profiles;this.quick=quick;this.metrics=metrics;}
    public Map<String,Object> classify(Map<String,Object> job,SaleManifest plan){
        long start=System.nanoTime();String observed="UNAVAILABLE";
        try{
            AttemptRiskProfiles.Snapshot p=profiles.read(Db.number(job,"user_id"),String.valueOf(job.get("device_id")),String.valueOf(job.get("ip")));observed=p.decision;
            Map<String,Object> policy=plan.rules().stream().filter(r->r.isEnabled()&&"DECISION_POLICY".equals(r.getRuleCode())).findFirst().orElseThrow(()->new IllegalStateException("Missing decision policy")).getConfig();
            String decision=p.decision,reason="PROFILE_"+decision;ReviewQuickCheck.Result result=null;
            if(!"BLOCK".equals(decision)){
                Object limit=policy.get("rateLimit");if(!(limit instanceof Number)||!Double.isFinite(((Number)limit).doubleValue())||((Number)limit).doubleValue()<1)throw new IllegalStateException("Invalid rate policy");
                String denied=quick.base(job,((Number)limit).doubleValue());
                if(denied!=null){decision="BLOCK";reason=denied;}
                else if("REVIEW".equals(decision)){result=quick.review(job,plan,p);reason=result.reason;if("REJECT".equals(result.decision))decision="BLOCK";}
            }
            Map<String,Object> admission=new LinkedHashMap<>();admission.put("qualificationMode","PROFILE_QUICK_V1");admission.put("decision",decision);
            admission.put("profileDecision",p.decision);admission.put("profileVersion",p.version);admission.put("score",p.score);admission.put("features",Collections.emptyMap());
            admission.put("matchedRules",Collections.singletonList(reason));admission.put("policy",new LinkedHashMap<>(policy));
            admission.put("resultCode","BLOCK".equals(decision)?"RISK_REJECTED":decision);
            if(result!=null){admission.put("quickDecision",result.decision);admission.put("reviewCacheHit",result.cached);}
            metrics.counter("attempt.qualification.decision","decision",decision).increment();return admission;
        }catch(Unavailable failure){throw failure;}
        catch(RuntimeException failure){throw new Unavailable("RISK_VALIDATION_UNAVAILABLE",failure);}
        finally{metrics.timer("attempt.qualification.duration","profile",observed).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
}
