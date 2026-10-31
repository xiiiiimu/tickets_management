package com.ticketing.sale;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.risk.RiskRule;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SaleManifestTest {
 @Test void publishedBytesAndNestedRuleConfigurationCannotBeMutated(){
  String payload="{\"sessionId\":\"s\",\"version\":1,\"bucketCount\":16,\"skus\":[{\"id\":\"sku\",\"price\":\"10.00\"}],\"rules\":[{\"ruleCode\":\"SIGNAL\",\"enabled\":true,\"weight\":10,\"version\":1,\"config\":{\"feature\":\"x\",\"nested\":{\"threshold\":1}}}]}";
  SaleManifest plan=new SaleManifest(payload,new ObjectMapper());
  RiskRule rule=plan.rules().get(0);rule.setWeight(999);
  assertEquals(10,plan.rules().get(0).getWeight());
  assertThrows(UnsupportedOperationException.class,()->((Map)rule.getConfig().get("nested")).put("threshold",999));
  assertThrows(UnsupportedOperationException.class,()->plan.sku("sku").put("price","1"));
  assertEquals(payload,plan.json());assertEquals(64,plan.digest().length());
  assertEquals(plan.digest(),new SaleManifest(payload,new ObjectMapper()).digest());
  assertNull(plan.sku("missing"));
 }
}
