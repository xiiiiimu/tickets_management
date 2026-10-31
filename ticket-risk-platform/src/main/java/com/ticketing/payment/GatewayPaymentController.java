package com.ticketing.payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@ConditionalOnProperty(name="ticketing.payment.gateway.enabled",havingValue="true")
public class GatewayPaymentController {
    private final GatewayPaymentService service;private final ObjectMapper json;private final String callbackKey;
    public GatewayPaymentController(GatewayPaymentService service,ObjectMapper json,@Value("${ticketing.payment.gateway.callback-key:}")String key){
        if(key.length()<32)throw new IllegalArgumentException("Gateway callback authentication key is missing");
        this.service=service;this.json=json;callbackKey=key;
    }
    @PostMapping("/api/tickets/orders/{id}/gateway-pay")
    public ResponseEntity<Result> pay(@PathVariable String id,@Valid @RequestBody PaymentRequest request){
        Map<String,Object> result=service.begin(id,UserHolder.getUser().getId(),request);
        return ResponseEntity.status("SUCCEEDED".equals(result.get("status"))?200:202).body(Result.ok(result));
    }
    @GetMapping("/api/tickets/payments/{id}") public Result status(@PathVariable String id){return Result.ok(service.view(id,UserHolder.getUser().getId()));}
    @lombok.Data public static class Callback {private String paymentAttemptId;private PaymentGateway.Observation observation;}
    @PostMapping("/api/payment-gateway/callback")
    public Result callback(@RequestHeader(value="X-Payment-Signature",defaultValue="")String signature,@RequestBody String body){
        if(body.length()>65536||!valid(callbackKey,signature,body))throw new BusinessException(401,"支付回调认证失败");
        try{
            Callback command=json.readValue(body,Callback.class);
            BusinessException.require(command.getPaymentAttemptId()!=null&&command.getObservation()!=null,"支付回调字段缺失");
            service.accept(command.getPaymentAttemptId(),command.getObservation(),null);return Result.ok();
        }catch(java.io.IOException invalid){throw new BusinessException(400,"支付回调格式无效");}
    }
    static boolean valid(String key,String signature,String body){
        if(!signature.matches("[0-9a-fA-F]{64}"))return false;
        try{
            Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] digest=hmac.doFinal(body.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();
            for(byte b:digest){hex.append(Character.forDigit((b&255)>>>4,16));hex.append(Character.forDigit(b&15,16));}
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        }catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
    }
}
