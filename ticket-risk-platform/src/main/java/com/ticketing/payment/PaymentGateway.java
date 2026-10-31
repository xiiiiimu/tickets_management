package com.ticketing.payment;
import java.math.BigDecimal;

/** Adapter contract: merchant attempt IDs are idempotent; CLOSED must fence even a delayed CREATE. */
public interface PaymentGateway {
    enum Status { NOT_FOUND, PENDING, SUCCESS, FAILED, CLOSED, REFUNDED }
    @lombok.Data class Observation {
        private String merchantPaymentId;
        private Status status;
        private String providerTransactionId;
        private BigDecimal amount;
        private String currency;
        private Long paidAt;
    }
    Observation create(String attempt,BigDecimal amount,String currency,long expiresAt);
    Observation query(String attempt);
    Observation close(String attempt);
    Observation refund(String attempt,String refundRequest,BigDecimal amount,String currency);
}
