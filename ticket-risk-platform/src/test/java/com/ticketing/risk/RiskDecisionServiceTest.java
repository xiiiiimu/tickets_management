package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.TicketOrder;
import com.ticketing.risk.rule.GenericThresholdRuleEvaluator;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskDecisionServiceTest {
    RiskFeatureService features;
    RiskAgentClient agent;
    RiskProfileService profiles;
    RiskDecisionService service;
    TicketOrder order;

    private static RiskRuleEngine engine() {
        return new RiskRuleEngine(new RiskRuleEvaluatorRegistry(
                Collections.singletonList(new GenericThresholdRuleEvaluator())));
    }

    @BeforeEach void setup() {
        features=mock(RiskFeatureService.class);
        agent=mock(RiskAgentClient.class);
        profiles=mock(RiskProfileService.class);
        RiskRule policy=new RiskRule();policy.setRuleCode("DECISION_POLICY");policy.setEnabled(true);
        Map<String,Object> config=new HashMap<>();
        config.put("review",50);config.put("block",100);config.put("agentMaxAdjustment",20);config.put("rateLimit",180);
        policy.setConfig(config);
        RiskRule signal=new RiskRule();signal.setRuleCode("SIGNAL");signal.setEnabled(true);signal.setWeight(90);
        Map<String,Object> condition=new HashMap<>();condition.put("feature","signal");condition.put("threshold",1);
        signal.setConfig(condition);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        service=spy(new RiskDecisionService(new Db(jdbc,new ObjectMapper()),features,
                engine(),agent,profiles,new ObjectMapper(),new SimpleMeterRegistry()));
        doReturn(Arrays.asList(policy,signal)).when(service).rules();
        order=new TicketOrder();order.setId("order-1");order.setUserId(1L);order.setAmount(new BigDecimal("380"));
        order.setDeviceId("device");order.setIp("ip");
        when(features.collect(1L,"device","ip",false,false,0)).thenReturn(Collections.singletonMap("signal",0d));
    }

    @Test void clearPassAndBlockNeverCallAgent() {
        assertEquals("PASS",service.evaluate(order,"RESERVE","device","ip").get("decision"));
        when(features.blacklisted(1L,"device","ip")).thenReturn(true);
        assertEquals("BLOCK",service.evaluate(order,"RESERVE","device","ip").get("decision"));
        when(features.blacklisted(1L,"device","ip")).thenReturn(false);
        service.rules().get(1).setWeight(100);
        when(features.collect(1L,"device","ip",false,false,0)).thenReturn(Collections.singletonMap("signal",1d));
        assertEquals("BLOCK",service.evaluate(order,"RESERVE","device","ip").get("decision"));
        verifyNoInteractions(agent);
        verify(profiles, atLeastOnce()).applyFromDecision(eq(order), anyMap());
    }

    @Test @SuppressWarnings("unchecked") void reviewUsesBoundedAdviceButJavaMakesFinalDecision() {
        when(features.collect(1L,"device","ip",false,false,0)).thenReturn(Collections.singletonMap("signal",1d));
        Map<String,Object> advice=new HashMap<>();advice.put("aiAvailable",true);advice.put("scoreAdjustment",999);
        advice.put("finalDecision","PASS");advice.put("suggestion","PASS");
        when(agent.analyze(anyMap())).thenReturn(advice);
        Map<String,Object> result=service.evaluate(order,"RESERVE","device","ip");
        assertEquals(110,result.get("score"));assertEquals("BLOCK",result.get("decision"));
        ArgumentCaptor<Map> input=ArgumentCaptor.forClass(Map.class);verify(agent).analyze(input.capture());
        assertEquals(new HashSet<>(Arrays.asList("order","features","matchedRules","userSummary",
                "deviceSummary","ipSummary","paymentSummary")),input.getValue().keySet());
        advice.put("scoreAdjustment",-999);
        assertEquals(90,service.evaluate(order,"RESERVE","device","ip").get("score"));
    }

    @Test void unavailableAgentLeavesJavaReviewAndScoreIntact() {
        when(features.collect(1L,"device","ip",false,false,0)).thenReturn(Collections.singletonMap("signal",1d));
        when(agent.analyze(anyMap())).thenReturn(RiskAgentClient.unavailable());
        Map<String,Object> result=service.evaluate(order,"RESERVE","device","ip");
        assertEquals("REVIEW",result.get("decision"));assertEquals(90,result.get("score"));
        verify(agent).analyze(anyMap());
        verify(profiles).applyFromDecision(eq(order), anyMap());
    }

    @Test void fastBlocksOnExistingProfileWithoutAgent() {
        when(features.collect(1L,"device","ip",true,false,0)).thenReturn(Collections.singletonMap("ipMinute",1d));
        when(features.blacklisted(1L,"device","ip")).thenReturn(false);
        RiskProfile block=new RiskProfile();
        block.setDecision("BLOCK");
        block.setExpiresAt(new java.sql.Timestamp(System.currentTimeMillis()+60_000));
        when(profiles.findBlocking(1L,"device","ip")).thenReturn(block);
        assertThrows(com.ticketing.common.BusinessException.class, () -> service.fast(1L,"device","ip"));
        verifyNoInteractions(agent);
    }

    @Test void httpFailureAndMissingResponseProduceExplicitZeroAdjustment() {
        RestTemplateBuilder builder=mock(RestTemplateBuilder.class,RETURNS_SELF);
        RestTemplate http=mock(RestTemplate.class);when(builder.build()).thenReturn(http);
        RiskAgentClient client=new RiskAgentClient(builder,"http://agent","test-key",new SimpleMeterRegistry());
        when(http.postForObject(anyString(),any(HttpEntity.class),eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(null)
                .thenReturn(Collections.singletonMap("aiAvailable",false));
        for(int i=0;i<3;i++) {
            Map<String,Object> result=client.analyze(Collections.emptyMap());
            assertEquals(false,result.get("aiAvailable"));assertEquals(0,result.get("scoreAdjustment"));
        }
        assertEquals("NOT_CONFIGURED",new RiskAgentClient(builder,"http://agent","",new SimpleMeterRegistry()).analyze(Collections.emptyMap()).get("executionStatus"));
    }
    @Test void persistenceDoesNotConsumeResultFields(){
        Map<String,Object> result=new HashMap<>();result.put("id","d");result.put("phase","POST_ISSUE");
        result.put("decision","REVIEW");result.put("score",90);result.put("features",Collections.emptyMap());
        result.put("matchedRules",Collections.singletonList("SIGNAL@1"));result.put("analysis",RiskAgentClient.unavailable());
        Map<String,Object> before=new HashMap<>(result);service.persist(order,Collections.unmodifiableMap(result));
        assertEquals(before,result);verify(profiles).applyFromDecision(eq(order),eq(before));
    }

}
