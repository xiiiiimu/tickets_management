package com.ticketing.risk;
import com.ticketing.infrastructure.Db;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import java.sql.Timestamp;
@Service
public class RiskFeatureService {
 @org.springframework.beans.factory.annotation.Autowired(required=false) private RiskHistorySummary summaries;
 private final Db db;private final StringRedisTemplate redis;private final DefaultRedisScript<Long> window=new DefaultRedisScript<>();
 public RiskFeatureService(Db db,StringRedisTemplate redis){this.db=db;this.redis=redis;window.setLocation(new ClassPathResource("lua/risk_sliding_window.lua"));window.setResultType(Long.class);}
 private double hit(String key,long period,String member){Long n=redis.execute(window,Collections.singletonList("risk:"+key),String.valueOf(period),member,member.startsWith("reservation:")?"ONCE":"");if(n==null)throw new IllegalStateException("Risk window unavailable");return n;}
 private double count(String key,long period){Long n=redis.opsForZSet().count("risk:"+key,System.currentTimeMillis()-period,Double.POSITIVE_INFINITY);return n==null?0:n;}
 public boolean blacklisted(long user,String device,String ip){return Boolean.TRUE.equals(redis.hasKey("risk:blacklist:user:"+user))||Boolean.TRUE.equals(redis.hasKey("risk:blacklist:device:"+device))||Boolean.TRUE.equals(redis.hasKey("risk:blacklist:ip:"+ip));}
 public Map<String,Double> collect(long user,String device,String ip,boolean record,boolean payment,double amount){
  return collect(user,device,ip,record,payment,amount,Db.id());
 }
 public Map<String,Double> collect(long user,String device,String ip,boolean record,boolean payment,double amount,String eventId){
  Map<String,Double> f=new HashMap<>();String member=eventId;
  f.put("ipMinute",record?hit("ip:"+ip,60000,member):count("ip:"+ip,60000));
  f.put("deviceMinute",record?hit("device:"+device,60000,member):count("device:"+device,60000));
  f.put("userMinute",record?hit("user:"+user,60000,member):count("user:"+user,60000));
  f.put("userTenMinutes",record&&!payment?hit("reserve:"+user,600000,member):count("reserve:"+user,600000));
  f.put("deviceAccounts",record?hit("deviceAccounts:"+device,600000,String.valueOf(user)):count("deviceAccounts:"+device,600000));
  f.put("ipAccounts",record?hit("ipAccounts:"+ip,600000,String.valueOf(user)):count("ipAccounts:"+ip,600000));
  f.put("paymentMinute",record&&payment?hit("pay:"+user,60000,member):count("pay:"+user,60000));
  Map<String,Double> summary=summaries==null?null:summaries.read(user,device);
  if(summary!=null){f.putAll(summary);}else{
  Map<String,Object> account=db.one("SELECT create_time FROM tb_user WHERE id=?",user);
  f.put("accountAgeHours",account==null?0d:(System.currentTimeMillis()-((Timestamp)account.get("create_time")).getTime())/3600000d);
  Map<String,Object> history=db.one("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status IN ('CANCELLED','PAY_TIMEOUT') THEN 1 ELSE 0 END),0) cancelled,COALESCE(SUM(CASE WHEN status='REFUNDED' THEN 1 ELSE 0 END),0) refunded,COALESCE(SUM(CASE WHEN status IN ('PAID','ISSUED') THEN 1 ELSE 0 END),0) succeeded FROM tb_ticket_order WHERE user_id=?",user);
  double total=Db.number(history,"total");f.put("historyCount",total);f.put("cancelRate",total==0?0:Db.number(history,"cancelled")/total);f.put("refundRate",total==0?0:Db.number(history,"refunded")/total);f.put("successOrders",(double)Db.number(history,"succeeded"));
  List<Map<String,Object>> payments=db.jdbc.queryForList("SELECT status FROM tb_payment_transaction WHERE user_id=? AND type='PAYMENT' ORDER BY created_at DESC,id DESC LIMIT 30",user);
  int failures=0;for(Map<String,Object> p:payments){if("SUCCESS".equals(p.get("status")))break;if("FAILED".equals(p.get("status")))failures++;}
  f.put("paymentFailures",(double)failures);f.put("paymentAmount",amount);
  long known=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE user_id=? AND device_id=? AND status='SUCCESS'",Long.class,user,device);
  f.put("newDevice",known==0?1d:0d);
  }
  f.put("paymentAmount",amount);
  redis.opsForHash().put("risk:feature:user:"+user,"snapshot",db.json(f));redis.expire("risk:feature:user:"+user,java.time.Duration.ofMinutes(10));return f;
 }
}
