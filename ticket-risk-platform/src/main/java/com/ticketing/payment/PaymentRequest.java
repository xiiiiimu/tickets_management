package com.ticketing.payment;
import lombok.Data;
import javax.validation.constraints.*;
@Data public class PaymentRequest {
 @NotBlank @Size(max=80) private String paymentRequestId;
 @NotBlank @Size(max=100) private String deviceId;
}
