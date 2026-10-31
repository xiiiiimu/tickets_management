package com.ticketing.risk;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.*;
import org.springframework.http.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
@Component
public class RiskAgentClient {
    private final RestTemplate http; private final String url,key; private final MeterRegistry metrics;
    public RiskAgentClient(RestTemplateBuilder builder,@Value("${ticketing.agent.url:http://localhost:8090}")String url,
            @Value("${ticketing.agent.key:}")String key,MeterRegistry metrics){
        http=builder.setConnectTimeout(Duration.ofMillis(400)).setReadTimeout(Duration.ofSeconds(2)).build();
        this.url=url;this.key=key;this.metrics=metrics;
    }
    public Map<String,Object> analyze(Map<String,Object> input){return analyzeTyped(input).toMap();}
    @SuppressWarnings("unchecked") public AgentAnalysis analyzeTyped(Map<String,Object> input){
        long begin=System.nanoTime(); AgentAnalysis result;
        if(key==null || key.isEmpty()) result=AgentAnalysis.failure(AgentExecutionStatus.NOT_CONFIGURED);
        else {
            metrics.counter("risk.agent.call").increment();
            try {
                HttpHeaders headers=new HttpHeaders();headers.set("X-Internal-Key",key);
                Map<String,Object> wire=http.postForObject(url+"/api/risk/analyze",new HttpEntity<>(input,headers),Map.class);
                result=AgentAnalysis.validate(wire);
            } catch(ResourceAccessException error){
                AgentExecutionStatus status=AgentExecutionStatus.CONNECT_ERROR;
                for(Throwable cause=error;cause!=null;cause=cause.getCause())
                    if(cause instanceof SocketTimeoutException){status=AgentExecutionStatus.TIMEOUT;break;}
                result=AgentAnalysis.failure(status);
            } catch(HttpStatusCodeException error){result=AgentAnalysis.failure(AgentExecutionStatus.HTTP_ERROR);
            } catch(IllegalArgumentException | RestClientException error){result=AgentAnalysis.failure(AgentExecutionStatus.INVALID_RESPONSE);
            } catch(Exception error){result=AgentAnalysis.failure(AgentExecutionStatus.ERROR);}
        }
        String status=result.getStatus().name();
        metrics.timer("risk.agent.http.duration","status",status).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);
        metrics.counter("risk.agent.execution","status",status).increment();
        if(result.getStatus()!=AgentExecutionStatus.SUCCESS){
            metrics.counter("risk.agent.unavailable").increment();
            if(result.getStatus()!=AgentExecutionStatus.UNAVAILABLE && result.getStatus()!=AgentExecutionStatus.NOT_CONFIGURED)
                metrics.counter("risk.agent.error").increment();
        }
        return result;
    }
    static Map<String,Object> unavailable(){return AgentAnalysis.failure(AgentExecutionStatus.UNAVAILABLE).toMap();}
}
