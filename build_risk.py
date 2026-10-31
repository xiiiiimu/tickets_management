from build_ticketing import java
java('risk/RiskDecision','''
package com.ticketing.risk;
public enum RiskDecision {PASS,REVIEW,BLOCK}
''')
java('risk/RiskRuleEngine','''
package com.ticketing.risk;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class RiskRuleEngine {
 public RiskScore evaluate(Map<String,Double> features,List<RiskRule> rules,boolean fast){
  int score=0;List<String> matches=new ArrayList<>();
  for(RiskRule r:rules){Map<String,Object> c=r.getConfig();
   if(!r.isEnabled()||!c.containsKey("feature")||(fast&&!Boolean.TRUE.equals(c.get("fast"))))continue;
   if(c.containsKey("maxAccountAgeHours")&&features.getOrDefault("accountAgeHours",Double.MAX_VALUE)>((Number)c.get("maxAccountAgeHours")).doubleValue())continue;
   if(c.containsKey("minHistory")&&features.getOrDefault("historyCount",0d)<((Number)c.get("minHistory")).doubleValue())continue;
   if(Boolean.TRUE.equals(c.get("requiresNewDevice"))&&features.getOrDefault("newDevice",0d)<1)continue;
   if(features.getOrDefault(c.get("feature").toString(),0d)>=((Number)c.get("threshold")).doubleValue()){
    score+=Math.max(0,r.getWeight());matches.add(r.getRuleCode()+"@"+r.getVersion());
   }
  }
  RiskScore result=new RiskScore();result.setValue(score);result.setMatchedRules(matches);return result;
 }
 public RiskDecision decide(int score,int review,int block){return score>=block?RiskDecision.BLOCK:score>=review?RiskDecision.REVIEW:RiskDecision.PASS;}
}
''')
java('risk/RiskFeatureService','''
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
 private final Db db;private final StringRedisTemplate redis;private final DefaultRedisScript<Long> window=new DefaultRedisScript<>();
 public RiskFeatureService(Db db,StringRedisTemplate redis){this.db=db;this.redis=redis;window.setLocation(new ClassPathResource("lua/risk_sliding_window.lua"));window.setResultType(Long.class);}
 private double hit(String key,long period,String member){Long n=redis.execute(window,Collections.singletonList("risk:"+key),String.valueOf(period),member);return n==null?0:n;}
 private double count(String key,long period){Long n=redis.opsForZSet().count("risk:"+key,System.currentTimeMillis()-period,Double.POSITIVE_INFINITY);return n==null?0:n;}
 public boolean blacklisted(long user,String device,String ip){return Boolean.TRUE.equals(redis.hasKey("risk:blacklist:user:"+user))||Boolean.TRUE.equals(redis.hasKey("risk:blacklist:device:"+device))||Boolean.TRUE.equals(redis.hasKey("risk:blacklist:ip:"+ip));}
 public Map<String,Double> collect(long user,String device,String ip,boolean record,boolean payment,double amount){
  Map<String,Double> f=new HashMap<>();String member=Db.id();
  f.put("ipMinute",record?hit("ip:"+ip,60000,member):count("ip:"+ip,60000));
  f.put("deviceMinute",record?hit("device:"+device,60000,member):count("device:"+device,60000));
  f.put("userMinute",record?hit("user:"+user,60000,member):count("user:"+user,60000));
  f.put("userTenMinutes",record&&!payment?hit("reserve:"+user,600000,member):count("reserve:"+user,600000));
  f.put("deviceAccounts",record?hit("deviceAccounts:"+device,600000,String.valueOf(user)):count("deviceAccounts:"+device,600000));
  f.put("ipAccounts",record?hit("ipAccounts:"+ip,600000,String.valueOf(user)):count("ipAccounts:"+ip,600000));
  f.put("paymentMinute",record&&payment?hit("pay:"+user,60000,member):count("pay:"+user,60000));
  Map<String,Object> account=db.one("SELECT create_time FROM tb_user WHERE id=?",user);
  f.put("accountAgeHours",account==null?0d:(System.currentTimeMillis()-((Timestamp)account.get("create_time")).getTime())/3600000d);
  Map<String,Object> history=db.one("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status IN ('CANCELLED','PAY_TIMEOUT') THEN 1 ELSE 0 END),0) cancelled,COALESCE(SUM(CASE WHEN status='REFUNDED' THEN 1 ELSE 0 END),0) refunded,COALESCE(SUM(CASE WHEN status IN ('PAID','ISSUED') THEN 1 ELSE 0 END),0) succeeded FROM tb_ticket_order WHERE user_id=?",user);
  double total=Db.number(history,"total");f.put("historyCount",total);f.put("cancelRate",total==0?0:Db.number(history,"cancelled")/total);f.put("refundRate",total==0?0:Db.number(history,"refunded")/total);f.put("successOrders",(double)Db.number(history,"succeeded"));
  List<Map<String,Object>> payments=db.jdbc.queryForList("SELECT status FROM tb_payment_transaction WHERE user_id=? AND type='PAYMENT' ORDER BY created_at DESC,id DESC LIMIT 30",user);
  int failures=0;for(Map<String,Object> p:payments){if("SUCCESS".equals(p.get("status")))break;if("FAILED".equals(p.get("status")))failures++;}
  f.put("paymentFailures",(double)failures);f.put("paymentAmount",amount);
  long known=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_transaction WHERE user_id=? AND device_id=? AND status='SUCCESS'",Long.class,user,device);
  f.put("newDevice",known==0?1d:0d);
  redis.opsForHash().put("risk:feature:user:"+user,"snapshot",db.json(f));redis.expire("risk:feature:user:"+user,java.time.Duration.ofMinutes(10));return f;
 }
}
''')
java('risk/RiskAgentClient','''
package com.ticketing.risk;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.*;
import java.time.Duration;
@Component
public class RiskAgentClient {
 private final RestTemplate http;private final String url,key;
 public RiskAgentClient(RestTemplateBuilder builder,@Value("${ticketing.agent.url:http://localhost:8090}")String url,@Value("${ticketing.agent.key:}")String key){http=builder.setConnectTimeout(Duration.ofMillis(400)).setReadTimeout(Duration.ofSeconds(2)).build();this.url=url;this.key=key;}
 @SuppressWarnings("unchecked") public Map<String,Object> analyze(Map<String,Object> input){
  if(key.isEmpty())return Collections.singletonMap("aiAvailable",false);
  try{HttpHeaders headers=new HttpHeaders();headers.set("X-Internal-Key",key);Map<String,Object> result=http.postForObject(url+"/api/risk/analyze",new HttpEntity<>(input,headers),Map.class);return result==null?Collections.singletonMap("aiAvailable",false):result;}
  catch(Exception e){return Collections.singletonMap("aiAvailable",false);}
 }
}
''')
java('risk/RiskDecisionService','''
package com.ticketing.risk;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
@Service
public class RiskDecisionService {
 private final Db db;private final RiskFeatureService features;private final RiskRuleEngine engine;private final RiskAgentClient agent;private final ObjectMapper json;private final MeterRegistry metrics;
 public RiskDecisionService(Db db,RiskFeatureService features,RiskRuleEngine engine,RiskAgentClient agent,ObjectMapper json,MeterRegistry metrics){this.db=db;this.features=features;this.engine=engine;this.agent=agent;this.json=json;this.metrics=metrics;}
 @SuppressWarnings("unchecked") public List<RiskRule> rules(){List<RiskRule> result=new ArrayList<>();for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_risk_rule ORDER BY rule_code")){
  try{RiskRule r=new RiskRule();r.setRuleCode(row.get("rule_code").toString());r.setEnabled(Boolean.parseBoolean(row.get("enabled").toString())||"1".equals(row.get("enabled").toString()));r.setWeight(((Number)row.get("weight")).intValue());r.setVersion(((Number)row.get("version")).intValue());r.setConfig(json.readValue(row.get("config").toString(),Map.class));result.add(r);}catch(Exception e){throw new IllegalStateException("Invalid risk rule",e);}
 }return result;}
 private Map<String,Object> policy(List<RiskRule> rules){return rules.stream().filter(r->r.getRuleCode().equals("DECISION_POLICY")&&r.isEnabled()).findFirst().orElseThrow(()->new IllegalStateException("Missing decision policy")).getConfig();}
 public void fast(long user,String device,String ip){
  List<RiskRule> rules=rules();Map<String,Object> p=policy(rules);Map<String,Double> f=features.collect(user,device,ip,true,false,0);
  if(f.get("ipMinute")>=((Number)p.get("rateLimit")).doubleValue())throw new BusinessException(429,"请求过于频繁");
  BusinessException.require(!features.blacklisted(user,device,ip),"请求已被风险策略阻止");
  RiskScore score=engine.evaluate(f,rules,true);BusinessException.require(engine.decide(score.getValue(),((Number)p.get("review")).intValue(),((Number)p.get("block")).intValue())!=RiskDecision.BLOCK,"请求已被风险策略阻止");
 }
 public Map<String,Object> evaluate(Map<String,Object> order,String phase,String device,String ip){
  long begin=System.nanoTime();try{
   List<RiskRule> rules=rules();Map<String,Object> p=policy(rules);boolean payment=phase.equals("PAYMENT");
   Map<String,Double> f=features.collect(Db.number(order,"user_id"),device,ip,payment,payment,payment?((Number)order.get("amount")).doubleValue():0);
   RiskScore score=engine.evaluate(f,rules,false);
   Map<String,Object> input=new LinkedHashMap<>();input.put("order",Collections.singletonMap("amount",order.get("amount")));input.put("features",f);input.put("matchedRules",score.getMatchedRules());
   input.put("userSummary",Collections.singletonMap("historyCount",f.get("historyCount")));input.put("deviceSummary",Collections.singletonMap("accounts",f.get("deviceAccounts")));input.put("ipSummary",Collections.singletonMap("requests",f.get("ipMinute")));input.put("paymentSummary",Collections.singletonMap("failures",f.get("paymentFailures")));
   Map<String,Object> analysis=agent.analyze(input);int adjusted=score.getValue();
   if(Boolean.TRUE.equals(analysis.get("aiAvailable"))&&analysis.get("scoreAdjustment") instanceof Number)adjusted+=Math.max(0,Math.min(((Number)p.get("agentMaxAdjustment")).intValue(),((Number)analysis.get("scoreAdjustment")).intValue()));
   RiskDecision decision=features.blacklisted(Db.number(order,"user_id"),device,ip)?RiskDecision.BLOCK:engine.decide(adjusted,((Number)p.get("review")).intValue(),((Number)p.get("block")).intValue());
   String id=Db.id();db.jdbc.update("INSERT INTO tb_risk_decision(id,order_id,phase,decision,score,features,matched_rules,agent_analysis) VALUES(?,?,?,?,?,?,?,?)",id,order.get("id"),phase,decision.name(),adjusted,db.json(f),db.json(score.getMatchedRules()),db.json(analysis));
   metrics.counter("risk."+decision.name().toLowerCase(Locale.ROOT)).increment();Map<String,Object> result=new HashMap<>();result.put("id",id);result.put("decision",decision.name());result.put("score",adjusted);return result;
  }finally{metrics.timer("risk.evaluate.latency").record(System.nanoTime()-begin,java.util.concurrent.TimeUnit.NANOSECONDS);}
 }
}
''')
