package com.ticketing.payment;

import com.ticketing.order.TicketOrder;
import com.ticketing.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Demo auto-payment confirmation (no wallet debit). Idempotent via {@code AUTO_PAY:&lt;orderNo&gt;}.
 * <p>
 * Delivery is durable: reserve TX writes {@code AUTO_PAYMENT} outbox → RocketMQ →
 * {@link com.ticketing.mq.TicketEventConsumer} calls {@link #attempt(String)}.
 * Manual review APPROVE may still call {@link #attempt(String)} synchronously.
 */
@Service
public class AutoPaymentService {
    private static final Logger log = LoggerFactory.getLogger(AutoPaymentService.class);

    private final OrderRepository orders;
    private final AutoPaymentExecutor executor;
    private final MeterRegistry metrics;

    public AutoPaymentService(OrderRepository orders, AutoPaymentExecutor executor, MeterRegistry metrics) {
        this.orders = orders;
        this.executor = executor;
        this.metrics = metrics;
    }

    public static String paymentRequestId(String orderNo) {
        return "AUTO_PAY:" + orderNo;
    }

    /**
     * Idempotent confirm when order is still {@code WAITING_PAYMENT}.
     * Safe under at-least-once MQ delivery (stable paymentRequestId).
     */
    public Map<String, Object> attempt(String orderId) {
        metrics.counter("payment.auto.attempt").increment();
        TicketOrder order = orders.findById(orderId);
        if (order == null || !"WAITING_PAYMENT".equals(order.getStatus())) {
            return null;
        }
        try {
            return executor.execute(orderId);
        } catch (RuntimeException e) {
            metrics.counter("payment.auto.failure").increment();
            log.error("auto pay confirm failed orderId=" + orderId, e);
            throw e;
        }
    }
}
