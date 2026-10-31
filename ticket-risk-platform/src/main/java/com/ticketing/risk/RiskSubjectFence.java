package com.ticketing.risk;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

/** Current-read SQL fences linearize a newly granted reservation against profile/blacklist writes. */
@Service
public class RiskSubjectFence {
    private final Db db;private final StringRedisTemplate redis;private final String shared;
    @Value("${ticketing.reservations-v2.enabled:false}") private boolean enabled;
    public RiskSubjectFence(Db db,StringRedisTemplate redis){
        this.db=db;this.redis=redis;
        String product=db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        shared=product!=null&&product.contains("MySQL")?" LOCK IN SHARE MODE":" FOR UPDATE";
    }
    public boolean isEnabled(){return enabled;}
    private List<String[]> subjects(long user,String device,String ip){
        List<String[]> values=new ArrayList<>(Arrays.asList(new String[]{"USER",Long.toString(user)},new String[]{"DEVICE",device},new String[]{"IP",ip}));
        values.removeIf(v->v[1]==null||v[1].isEmpty());values.sort(Comparator.comparing(v->v[0]+":"+v[1]));return values;
    }
    public void prepare(long user,String device,String ip){
        requireTransaction();for(String[] v:subjects(user,device,ip))ensure(v[0],v[1]);
    }
    private void ensure(String type,String value){
        if(db.one("SELECT subject_value FROM tb_risk_subject_guard WHERE subject_type=? AND subject_value=?",type,value)==null)
            db.jdbc.update("INSERT INTO tb_risk_subject_guard(subject_type,subject_value) VALUES(?,?) ON DUPLICATE KEY UPDATE subject_value=subject_value",type,value);
    }
    public void exclusive(long user,String device,String ip){
        if(!enabled)return;prepare(user,device,ip);
        for(String[] v:subjects(user,device,ip))exclusiveOne(v[0],v[1]);
    }
    public void exclusiveOne(String type,String value){
        if(!enabled)return;requireTransaction();ensure(type,value);
        db.one("SELECT subject_value FROM tb_risk_subject_guard WHERE subject_type=? AND subject_value=? FOR UPDATE",type,value);
    }
    public boolean lockAndCheck(long user,String device,String ip){
        requireTransaction();boolean blocked=false;List<String[]> subjects=subjects(user,device,ip);
        List<Object> args=new ArrayList<>();List<String> predicates=new ArrayList<>();
        for(String[] v:subjects){predicates.add("(g.subject_type=? AND g.subject_value=?)");args.add(v[0]);args.add(v[1]);}
        List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT g.blocked_until,p.decision,p.expires_at FROM tb_risk_subject_guard g "
                +"LEFT JOIN tb_risk_profile p ON p.subject_type=g.subject_type AND p.subject_value=g.subject_value WHERE "
                +String.join(" OR ",predicates)+" ORDER BY g.subject_type,g.subject_value"+shared,args.toArray());
        BusinessException.require(rows.size()==subjects.size(),"风险限制校验未就绪");
        // Current locking read: new BLOCK and guard versions cannot race past this grant transaction.
        for(Map<String,Object> row:rows){
            if(row.get("blocked_until")!=null&&((Timestamp)row.get("blocked_until")).getTime()>System.currentTimeMillis())blocked=true;
            if("BLOCK".equals(row.get("decision"))&&row.get("expires_at")!=null
                    &&((Timestamp)row.get("expires_at")).getTime()>System.currentTimeMillis())blocked=true;
        }
        for(String[] v:subjects){
            // Existing Redis blacklists remain enforced too; Redis read failures propagate, never allow.
            if(Boolean.TRUE.equals(redis.hasKey("risk:blacklist:"+v[0].toLowerCase(Locale.ROOT)+":"+v[1])))blocked=true;
        }
        return !blocked;
    }
    @Transactional
    public void blacklist(String kind,String value,long seconds){
        requireTransaction();String type=kind.toUpperCase(Locale.ROOT);exclusiveOne(type,value);
        db.jdbc.update("UPDATE tb_risk_subject_guard SET blocked_until=?,version=version+1 WHERE subject_type=? AND subject_value=?",
                new Timestamp(System.currentTimeMillis()+seconds*1000),type,value);
        // Same transaction's X fence excludes new grants until the Redis write and DB commit complete.
        redis.opsForValue().set("risk:blacklist:"+kind+":"+value,"1",Duration.ofSeconds(seconds));
    }
    private void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Risk fence needs transaction");}
}
