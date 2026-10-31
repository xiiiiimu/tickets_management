package com.ticketing.payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GatewayCallbackAuthenticationTest {
    @Test void signatureBindsExactBodyAndInvalidCallerNeverReachesDatabase()throws Exception{
        String key="test-only-callback-key-not-a-secret-32",body="{\"paymentAttemptId\":\"id\",\"observation\":{\"merchantPaymentId\":\"id\",\"status\":\"PENDING\"}}";
        GatewayPaymentService service=mock(GatewayPaymentService.class);GatewayPaymentController controller=new GatewayPaymentController(service,new ObjectMapper(),key);
        assertThrows(BusinessException.class,()->controller.callback("",body));verifyNoInteractions(service);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));StringBuilder signature=new StringBuilder();
        for(byte b:mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))signature.append(String.format("%02x",b&255));
        assertTrue(GatewayPaymentController.valid(key,signature.toString(),body));
        assertThrows(BusinessException.class,()->controller.callback(signature.toString(),body+" "));verifyNoInteractions(service);
        controller.callback(signature.toString(),body);verify(service).accept(eq("id"),any(),isNull());
    }
}
