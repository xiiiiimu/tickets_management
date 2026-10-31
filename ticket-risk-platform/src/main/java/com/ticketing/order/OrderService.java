package com.ticketing.order;

import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.OrderRepository;
import com.ticketing.repository.ReservationRepository;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.repository.UserPurchaseRepository;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final ReservationRepository reservations;
    private final TicketInventoryRepository skus;
    private final UserPurchaseRepository purchases;
    private final OrderStateMachine machine;
    private final OutboxService outbox;
    private final WorkflowExecutionService workflow;
    private final MeterRegistry metrics;

    public OrderService(OrderRepository orders, ReservationRepository reservations, TicketInventoryRepository skus,
                        UserPurchaseRepository purchases, OrderStateMachine machine, OutboxService outbox,
                        WorkflowExecutionService workflow, MeterRegistry metrics) {
        this.orders = orders;
        this.reservations = reservations;
        this.skus = skus;
        this.purchases = purchases;
        this.machine = machine;
        this.outbox = outbox;
        this.workflow = workflow;
        this.metrics = metrics;
    }

    public TicketOrder lock(String id) {
        TicketOrder order = orders.findByIdForUpdate(id);
        if (order == null) throw new BusinessException(404, "订单不存在");
        return order;
    }

    public TicketOrder find(String id) {
        TicketOrder order = orders.findById(id);
        if (order == null) throw new BusinessException(404, "订单不存在");
        return order;
    }

    public void owner(TicketOrder order, long user) {
        if (order.getUserId() != user) throw new BusinessException(403, "无权访问该订单");
    }

    public void release(TicketOrder order) {
        if (reservations.tryReleaseHeld(order.getReservationId())) {
            skus.increaseStockForReservation(order.getReservationId(),order.getSkuId(),order.getQuantity());
            purchases.decreaseQuantity(order.getUserId(), order.getSkuId(), order.getQuantity());
            outbox.append(order.getId(), "RELEASE");
        }
    }

    @Transactional
    public TicketOrder cancel(String id, long user) {
        TicketOrder order = lock(id);
        owner(order, user);
        if ("CANCELLED".equals(order.getStatus())) return order;
        machine.transition(order, OrderStatus.CANCELLED);
        release(order);
        metrics.counter("order.cancelled").increment();
        workflow.record(id, Objects.toString(order.getTraceId(), ""), "CANCEL", order.getStatus());
        return order;
    }

    @Transactional
    public void expire(String id) {
        TicketOrder order = lock(id);
        if (order.getExpiresAt().getTime() > System.currentTimeMillis()) return;
        // Even if future gateway transitions add PAYING edges, unknown payment may never release blindly.
        if("PAYING".equals(order.getStatus()))return;
        if (OrderStateMachine.permits(OrderStatus.valueOf(order.getStatus()), OrderStatus.PAY_TIMEOUT)) {
            machine.transition(order, OrderStatus.PAY_TIMEOUT);
            release(order);
            metrics.counter("order.cancelled").increment();
            workflow.record(id, Objects.toString(order.getTraceId(), ""), "PAY_TIMEOUT", order.getStatus());
        }
    }
}
