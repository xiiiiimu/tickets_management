package com.ticketing.repository;

import java.util.Map;

/**
 * Payment / refund rows on {@code tb_payment_transaction}.
 */
public interface PaymentRepository {

    Map<String, Object> findByPaymentRequestId(String paymentRequestId);

    Map<String, Object> findById(String id);

    void insert(String id, String paymentRequestId, String orderId, long userId, Object amount,
                String type, String status, String successKey, String deviceId);
}
