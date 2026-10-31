package com.ticketing.ticket;
import lombok.Data;
import javax.validation.constraints.*;
@Data
public class ReserveRequest {
 @NotBlank @Size(max=80) private String requestId;
 @NotNull @Positive private Long userId;
 @NotBlank @Size(max=36) private String eventSessionId;
 @NotBlank @Size(max=36) private String ticketSkuId;
 @Min(1) @Max(100) private int quantity;
 @NotBlank @Size(max=100) private String deviceId;
}
