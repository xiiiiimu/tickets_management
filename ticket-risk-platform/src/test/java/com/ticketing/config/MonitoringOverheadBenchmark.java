package com.ticketing.config;
import com.ticketing.risk.RiskFeatureService;
import com.ticketing.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheus.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit opt-in microbenchmark; not a production throughput claim. */
class MonitoringOverheadBenchmark {
 private long time(RiskFeatureService target,int n){long begin=System.nanoTime();for(int i=0;i<n;i++)target.collect(1,"d","i",false,false,0);return System.nanoTime()-begin;}
 @Test void compareProxyInstrumentationAndScrapeDatabaseCalls(){
  BusinessMetricsAspectTest.Features raw=new BusinessMetricsAspectTest.Features();
  AspectJProxyFactory offFactory=new AspectJProxyFactory(raw);offFactory.setProxyTargetClass(true);
  RiskFeatureService off=offFactory.getProxy();
  PrometheusMeterRegistry registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  AspectJProxyFactory onFactory=new AspectJProxyFactory(raw);onFactory.setProxyTargetClass(true);onFactory.addAspect(new BusinessMetricsAspect(registry));
  RiskFeatureService on=onFactory.getProxy();
  time(off,20000);time(on,20000);long[] offNs=new long[7],onNs=new long[7];int n=100000;
  for(int i=0;i<7;i++){if(i%2==0){offNs[i]=time(off,n);onNs[i]=time(on,n);}else{onNs[i]=time(on,n);offNs[i]=time(off,n);}}
  Arrays.sort(offNs);Arrays.sort(onNs);
  JdbcTemplate jdbc=mock(JdbcTemplate.class);PlatformMetrics metrics=new PlatformMetrics(new Db(jdbc,new ObjectMapper()),registry);
  long begin=System.nanoTime();for(int i=0;i<200;i++)registry.scrape();long scrapeNs=System.nanoTime()-begin;
  verifyNoInteractions(jdbc);
  when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());
  when(jdbc.queryForObject(anyString(),eq(Double.class))).thenReturn(1d);
  metrics.refreshSnapshot();assertEquals(3,mockingDetails(jdbc).getInvocations().size());
  System.out.printf(java.util.Locale.ROOT,"MONITORING_BENCHMARK off_ns_per_call=%.2f on_ns_per_call=%.2f delta_ns=%.2f scrape_us=%.2f scrape_db_calls=0 sample_db_calls=3%n",
    offNs[3]/(double)n,onNs[3]/(double)n,(onNs[3]-offNs[3])/(double)n,scrapeNs/200000.0);
 }
}
