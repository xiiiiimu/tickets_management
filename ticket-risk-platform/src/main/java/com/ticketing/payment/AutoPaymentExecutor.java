package com.ticketing.payment;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.OrderService;
import com.ticketing.order.OrderStatus;
import com.ticketing.order.OrderStateMachine;
import com.ticketing.order.TicketOrder;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.PaymentRepository;
import com.ticketing.workflow.service.WorkflowExecutionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Local auto-payment confirmation (no wallet debit / third-party).
 * Runs in {@code REQUIRES_NEW}: Payment SUCCESS + WAITING_PAYMENT→PAYING→PAID + ISSUE outbox.
 */
@Service
public class AutoPaymentExecutor {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationPaymentPolicy reservationPolicy;
    private final OrderService orders;
    private final OrderStateMachine machine;
    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final WorkflowExecutionService workflow;
    private final MeterRegistry metrics;

    public AutoPaymentExecutor(OrderService orders, OrderStateMachine machine, PaymentRepository payments,
                               OutboxService outbox, WorkflowExecutionService workflow, MeterRegistry metrics) {
        this.orders = orders;
        this.machine = machine;
        this.payments = payments;
        this.outbox = outbox;
        this.workflow = workflow;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> execute(String orderId) {
        TicketOrder order = orders.lock(orderId);
        String requestId = AutoPaymentService.paymentRequestId(order.getOrderNo());
        Map<String, Object> prior = payments.findByPaymentRequestId(requestId);
        if (prior != null) {
            BusinessException.require(
                    orderId.equals(String.valueOf(prior.get("order_id")))
                            && order.getUserId() == Db.number(prior, "user_id")
                            && "PAYMENT".equals(prior.get("type")),
                    "自动支付请求标识与原请求不一致");
            return prior;
        }
        if (!"WAITING_PAYMENT".equals(order.getStatus())) {
            // Already advanced (e.g. concurrent confirm) — no second SUCCESS insert.
            return null;
        }
        boolean v2=reservationPolicy!=null&&reservationPolicy.isV2(orderId);
        if(v2&&!reservationPolicy.allowsMock())return null;
        if(v2)reservationPolicy.assertQualified(order);
        String deviceId = order.getDeviceId() == null || order.getDeviceId().isEmpty() ? "AUTO" : order.getDeviceId();
        if(v2)reservationPolicy.startPaying(order);
        else machine.transition(order, OrderStatus.PAYING);
        machine.transition(order, OrderStatus.PAID);
        String paymentId = Db.id();
        payments.insert(paymentId, requestId, orderId, order.getUserId(), order.getAmount(),
                "PAYMENT", "SUCCESS", "PAYMENT:" + orderId, deviceId);
        outbox.append(orderId, "ISSUE");
        workflow.record(orderId, Objects.toString(order.getTraceId(), ""), "PAYMENT", "SUCCESS");
        metrics.counter("payment.auto.success").increment();
        return payments.findById(paymentId);
    }
}
