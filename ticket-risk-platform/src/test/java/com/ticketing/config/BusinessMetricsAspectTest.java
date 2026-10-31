package com.ticketing.config;
import com.ticketing.risk.RiskFeatureService;
import com.ticketing.infrastructure.Db;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class BusinessMetricsAspectTest {
 static class Features extends RiskFeatureService {
  Features(){super(mock(Db.class),mock(StringRedisTemplate.class));}
  @Override public Map<String,Double> collect(long u,String d,String ip,boolean r,boolean p,double a){
   if(u<0)throw new IllegalArgumentException("failure");return Collections.emptyMap();
  }
 }
 @Test void timesActualProxyCallsAndPropagatesFailures(){
  SimpleMeterRegistry registry=new SimpleMeterRegistry();AspectJProxyFactory factory=new AspectJProxyFactory(new Features());
  factory.setProxyTargetClass(true);factory.addAspect(new BusinessMetricsAspect(registry));
  RiskFeatureService features=factory.getProxy();
  assertEquals(Collections.emptyMap(),features.collect(1,"d","i",true,false,0));
  assertThrows(IllegalArgumentException.class,()->features.collect(-1,"d","i",true,false,0));
  assertEquals(1,registry.get("ticketing.operation.duration").tags("operation","features","outcome","success").timer().count());
  assertEquals(1,registry.get("ticketing.operation.duration").tags("operation","features","outcome","failure").timer().count());
 }
}
