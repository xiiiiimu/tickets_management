package benchmark;

import com.ticketing.TicketPlatformApplication;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;

/** Test classpath only: measures inventory without risk; never packaged in the application JAR. */
public class InventoryOnlyApplication {
    public static void main(String[] args){SpringApplication.run(new Class<?>[]{TicketPlatformApplication.class,PureInventory.class},args);}
    @TestConfiguration
    public static class PureInventory {
        private void isolated(Environment env){
            String url=env.getRequiredProperty("spring.datasource.url");
            if(!url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_v2_bench_")
                    || !"26379".equals(env.getProperty("spring.redis.port"))
                    || !"127.0.0.1".equals(env.getProperty("server.address")))
                throw new IllegalStateException("Inventory-only benchmark requires its isolated local test environment");
        }
        @Bean @Primary
        RiskDecisionService inventoryOnlyRisk(Db db,RiskFeatureService f,RiskRuleEngine engine,RiskAgentClient agent,
                RiskProfileService profiles,ObjectMapper json,MeterRegistry metrics,Environment env){
            isolated(env);
            return new RiskDecisionService(db,f,engine,agent,profiles,json,metrics){
                @Override public Map<String,Object> classifyReservation(long user,String device,String ip,List<RiskRule> rules,String request){
                    Map<String,Object> result=new LinkedHashMap<>();result.put("decision","PASS");result.put("score",0);
                    result.put("features",Collections.emptyMap());result.put("matchedRules",Collections.emptyList());
                    result.put("policy",rules.stream().filter(r->"DECISION_POLICY".equals(r.getRuleCode())).findFirst().get().getConfig());
                    result.put("resultCode","INVENTORY_ONLY_TEST");return result;
                }
            };
        }
        @Bean @Primary
        RiskSubjectFence inventoryOnlyFence(Db db,StringRedisTemplate redis,Environment env){
            isolated(env);
            return new RiskSubjectFence(db,redis){
                @Override public void prepare(long user,String device,String ip){}
                @Override public boolean lockAndCheck(long user,String device,String ip){return true;}
            };
        }
    }
}
