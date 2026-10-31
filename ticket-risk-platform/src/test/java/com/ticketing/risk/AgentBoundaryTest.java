package com.ticketing.risk;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AgentBoundaryTest {
    Map<String,Object> valid(){
        Map<String,Object> m=new HashMap<>();m.put("aiAvailable",true);m.put("riskLevel","MEDIUM");
        m.put("scoreAdjustment",20);m.put("confidence",0.1);m.put("reasons",Arrays.asList("signal"));m.put("suggestion","investigate");return m;
    }
    @Test void rejectsMalformedAvailableResponses(){
        for(String field:Arrays.asList("riskLevel","scoreAdjustment","confidence","reasons","suggestion")){
            Map<String,Object> m=valid();m.remove(field);assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
        }
        for(Object value:Arrays.asList(Double.NaN,Double.POSITIVE_INFINITY,-0.1,1.1,"0.9")){
            Map<String,Object> m=valid();m.put("confidence",value);assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
        }
        for(Object value:Arrays.asList(21,-21,0.5,Double.NaN)){
            Map<String,Object> m=valid();m.put("scoreAdjustment",value);assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
        }
        Map<String,Object> m=valid();m.put("reasons",Arrays.asList(" "));assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
        m.put("reasons",Arrays.asList(1));assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
        m.put("riskLevel","PASS");assertThrows(IllegalArgumentException.class,()->AgentAnalysis.validate(m));
    }
    @Test void validatedResponseIsDetachedAndImmutable(){
        Map<String,Object> m=valid();List<String> reasons=new ArrayList<>(Arrays.asList("evidence"));m.put("reasons",reasons);
        Map<String,Object> result=AgentAnalysis.validate(m).toMap();reasons.clear();
        assertEquals(Arrays.asList("evidence"),result.get("reasons"));
        assertThrows(UnsupportedOperationException.class,()->result.put("confidence",0));
    }
    @Test void preservesLowConfidenceBlockAndIgnoresNegativeAdjustment(){
        Map<String,Object> policy=new HashMap<>();policy.put("review",50);policy.put("block",100);policy.put("agentMaxAdjustment",20);
        Map<String,Object> advice=valid();
        assertEquals(RiskDecision.BLOCK,RiskAdjudicator.decide(90,RiskDecision.REVIEW,policy,advice).getDecision());
        advice.put("scoreAdjustment",-20);
        assertEquals(90,RiskAdjudicator.decide(90,RiskDecision.REVIEW,policy,advice).getScore());
        assertEquals(RiskDecision.REVIEW,RiskAdjudicator.decide(90,RiskDecision.REVIEW,policy,RiskAgentClient.unavailable()).getDecision());
    }
    @Test void distinguishesTransportAndValidationFailures(){
        RestTemplateBuilder builder=mock(RestTemplateBuilder.class,RETURNS_SELF);RestTemplate http=mock(RestTemplate.class);
        when(builder.build()).thenReturn(http);RiskAgentClient client=new RiskAgentClient(builder,"http://agent","key",new SimpleMeterRegistry());
        when(http.postForObject(anyString(),any(HttpEntity.class),eq(Map.class)))
            .thenThrow(new ResourceAccessException("read",new SocketTimeoutException()))
            .thenThrow(new ResourceAccessException("connect",new ConnectException()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(Collections.singletonMap("aiAvailable",true))
            .thenReturn(Collections.singletonMap("aiAvailable",false)).thenReturn(valid());
        for(String expected:Arrays.asList("TIMEOUT","CONNECT_ERROR","HTTP_ERROR","INVALID_RESPONSE","UNAVAILABLE","SUCCESS"))
            assertEquals(expected,client.analyze(Collections.emptyMap()).get("executionStatus"));
    }
}
