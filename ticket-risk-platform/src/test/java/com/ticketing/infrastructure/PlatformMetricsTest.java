package com.ticketing.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheus.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class PlatformMetricsTest {
 @Test void bucketedInventoryGaugeUsesAuthoritativeBalancesInsteadOfStaleSkuTotal(){
  org.h2.jdbcx.JdbcDataSource source=new org.h2.jdbcx.JdbcDataSource();source.setURL("jdbc:h2:mem:metrics-buckets;MODE=MySQL;DB_CLOSE_DELAY=-1");
  JdbcTemplate jdbc=new JdbcTemplate(source);
  jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR(36) PRIMARY KEY,remaining_stock INT)");
  jdbc.execute("CREATE TABLE tb_ticket_stock_bucket(sku_id VARCHAR(36),bucket_id INT,remaining_stock INT)");
  jdbc.execute("CREATE TABLE tb_outbox_message(status VARCHAR(20))");
  jdbc.execute("CREATE TABLE tb_risk_profile(decision VARCHAR(10),expires_at TIMESTAMP)");
  jdbc.update("INSERT INTO tb_ticket_sku VALUES('assembled',1000),('unassembled',50)");
  for(int i=0;i<16;i++)jdbc.update("INSERT INTO tb_ticket_stock_bucket VALUES('assembled',?,2)",i);
  PrometheusMeterRegistry registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  PlatformMetrics metrics=new PlatformMetrics(new Db(jdbc,new ObjectMapper()),registry);
  org.springframework.test.util.ReflectionTestUtils.setField(metrics,"bucketCount",16);
  metrics.refreshSnapshot();assertEquals(82d,registry.get("ticket.stock.remaining").gauge().value());
 }
 @Test void scrapesNeverQueryDatabaseAndFailureRetainsSample(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);PrometheusMeterRegistry registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  PlatformMetrics metrics=new PlatformMetrics(new Db(jdbc,new ObjectMapper()),registry);
  for(int i=0;i<10;i++)registry.scrape();verifyNoInteractions(jdbc);
  when(jdbc.queryForList(anyString())).thenReturn(Collections.singletonList(new HashMap<String,Object>(){{put("status","RETRY");put("total",3L);}}));
  when(jdbc.queryForObject(anyString(),eq(Double.class))).thenReturn(7d);
  metrics.refreshSnapshot();assertEquals(3d,registry.get("mq.outbox.pending").gauge().value());
  clearInvocations(jdbc);for(int i=0;i<100;i++)registry.scrape();verifyNoInteractions(jdbc);
  when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("db down"));
  metrics.refreshSnapshot();assertEquals(3d,registry.get("mq.outbox.pending").gauge().value());
  assertEquals(1d,registry.get("platform.snapshot.failure").counter().count());
 }
}
