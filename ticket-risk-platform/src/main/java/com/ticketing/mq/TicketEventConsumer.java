package com.ticketing.mq;

import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.order.*;
import com.ticketing.payment.AutoPaymentService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.IssuedTicketRepository;
import com.ticketing.repository.MessageConsumeRepository;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumes ticket-events.
 * RISK_CHECK: post-trade RiskProfile only.
 * AUTO_PAYMENT: durable auto-pay (idempotent {@link AutoPaymentService#attempt}).
 * ISSUE / RELEASE: fulfillment.
 */
@Service
public class TicketEventConsumer {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.ticketing.notification.OrderNotificationService notifications;
    private final MessageConsumeRepository consumes;
    private final IssuedTicketRepository tickets;
    private final OrderService orders;
    private final OrderStateMachine machine;
    private final RiskDecisionService risk;
    private final InventoryService inventory;
    private final AutoPaymentService autoPayment;
    private final WorkflowExecutionService workflow;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final OutboxService outbox;

    public TicketEventConsumer(MessageConsumeRepository consumes, IssuedTicketRepository tickets, OrderService orders,
                               OrderStateMachine machine, RiskDecisionService risk,
                               InventoryService inventory, AutoPaymentService autoPayment,
                               WorkflowExecutionService workflow, MeterRegistry metrics,
                               PlatformTransactionManager transactionManager, OutboxService outbox) {
        this.consumes = consumes;
        this.tickets = tickets;
        this.orders = orders;
        this.machine = machine;
        this.risk = risk;
        this.inventory = inventory;
        this.autoPayment = autoPayment;
        this.workflow = workflow;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
    }

    public void consume(String key, String orderId, String type) {
        BusinessException.require(key.equals(type + ":" + orderId), "消息业务键不匹配");
        BusinessException.require(
                Arrays.asList("RISK_CHECK", "ISSUE", "RELEASE", "AUTO_PAYMENT","RESERVATION_CREATED","NOTIFY").contains(type), "未知消息类型");
        if ("AUTO_PAYMENT".equals(type)) {
            consumeAutoPayment(key, orderId);
            return;
        }
        if ("RISK_CHECK".equals(type)) {
            consumeRisk(key, orderId);
            return;
        }
        tx.executeWithoutResult(status -> consumeOther(key, orderId, type));
    }

    /**
     * Must not hold an outer DB transaction across {@link AutoPaymentService#attempt}:
     * attempt opens REQUIRES_NEW and would otherwise pin 2 Hikari connections per MQ thread.
     */
    private void consumeAutoPayment(String key, String orderId) {
        Boolean duplicate = tx.execute(status -> consumes.findByBusinessKey(key) != null);
        if (Boolean.TRUE.equals(duplicate)) {
            metrics.counter("mq.consume.duplicate").increment();
            return;
        }
        // System failures propagate for MQ retry; paymentRequestId makes redelivery safe.
        autoPayment.attempt(orderId);
        tx.executeWithoutResult(status -> {
            if (consumes.findByBusinessKey(key) != null) {
                return;
            }
            consumes.insertProcessing(key, key);
            consumes.markDone(key);
        });
        metrics.counter("payment.auto.mq.consumed").increment();
    }

    private void consumeRisk(String key, String orderId) {
        if (consumes.findByBusinessKey(key) != null) {
            metrics.counter("mq.consume.duplicate").increment();
            return;
        }
        TicketOrder order = orders.find(orderId); // Plain read: never lock a trading order for AI.
        Map<String, Object> decision = risk.investigate(order); // No transaction / connection across HTTP.
        tx.executeWithoutResult(status -> {
            if (consumes.findByBusinessKey(key) != null) return;
            consumes.insertProcessing(key, key); // Unique business key serializes concurrent redeliveries.
            if (decision != null) {
                risk.persist(order, decision);
                if(notifications!=null)workflow.recordInvestigationEvidence(orderId,Objects.toString(order.getTraceId(),""),decision);
                else workflow.record(orderId, Objects.toString(order.getTraceId(), ""), "RISK_ANALYZE", decision);
                metrics.counter("risk.analyze.completed").increment();
            }
            consumes.markDone(key);
        });
    }

    private void consumeOther(String key, String orderId, String type) {
        if (consumes.findByBusinessKey(key) != null) {
            metrics.counter("mq.consume.duplicate").increment();
            return;
        }
        consumes.insertProcessing(key, key);

        TicketOrder order = orders.lock(orderId);
        if (type.equals("ISSUE") && "PAID".equals(order.getStatus())) {
            for (int i = 1; i <= order.getQuantity(); i++)
                tickets.insert(Db.id(), orderId, i);
            machine.transition(order, OrderStatus.ISSUED);
            if(notifications!=null){notifications.enqueue(order);outbox.append(orderId,"NOTIFY");}
            if (risk.needsInvestigation(orderId)) outbox.append(orderId, "RISK_CHECK");
            workflow.record(orderId, Objects.toString(order.getTraceId(), ""), "ISSUE", order.getQuantity());
        } else if(type.equals("RESERVATION_CREATED")){
            workflow.recordReservationEvidence(orderId,Objects.toString(order.getTraceId(),""),order.getReservationId());
        } else if(type.equals("NOTIFY")){
            BusinessException.require(notifications!=null,"通知处理器未启用，需重试");notifications.deliver(orderId);
        } else if (type.equals("RELEASE"))
            inventory.release(order.getSkuId(), order.getUserId(), order.getReservationId());
        consumes.markDone(key);
    }
}
