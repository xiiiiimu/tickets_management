package com.ticketing.payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Explicit opt-in generic adapter. A provider must implement the documented idempotent close/refund contract. */
@Component
@ConditionalOnProperty(name="ticketing.payment.gateway.enabled",havingValue="true")
public class HttpPaymentGateway implements PaymentGateway {
    private final RestTemplate http;private final String base;
    public HttpPaymentGateway(RestTemplateBuilder builder,@Value("${ticketing.payment.gateway.url:}")String base,
            @Value("${ticketing.payment.gateway.key:}")String key){
        if(!base.startsWith("https://")&&!base.startsWith("http://127.0.0.1:"))throw new IllegalArgumentException("Gateway needs explicit HTTPS or loopback test URL");
        if(key.length()<16)throw new IllegalArgumentException("Gateway credential is missing");
        this.base=base.replaceAll("/$","");http=builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(3)).defaultHeader("Authorization","Bearer "+key).build();
    }
    private Map<String,Object> body(String attempt,BigDecimal amount,String currency){Map<String,Object> m=new LinkedHashMap<>();m.put("merchantPaymentId",attempt);m.put("amount",amount);m.put("currency",currency);return m;}
    public Observation create(String attempt,BigDecimal amount,String currency,long expiry){Map<String,Object> m=body(attempt,amount,currency);m.put("expiresAt",expiry);return http.postForObject(base+"/payments",m,Observation.class);}
    public Observation query(String attempt){return http.getForObject(base+"/payments/"+attempt,Observation.class);}
    public Observation close(String attempt){return http.postForObject(base+"/payments/"+attempt+"/close",Collections.singletonMap("merchantPaymentId",attempt),Observation.class);}
    public Observation refund(String attempt,String request,BigDecimal amount,String currency){Map<String,Object> m=body(attempt,amount,currency);m.put("refundRequestId",request);return http.postForObject(base+"/refunds",m,Observation.class);}
}
