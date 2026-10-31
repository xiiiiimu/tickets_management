package com.ticketing.ticket;

import com.ticketing.inventory.InventoryService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.config.RequestTimings;
import com.ticketing.repository.OrderRepository;
import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.repository.ReservationRepository;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.repository.UserPurchaseRepository;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reserve → WAITING_PAYMENT order + AUTO_PAYMENT outbox, with a snapshot for REVIEW.
 * Investigation is scheduled by ISSUE, after fulfillment succeeds.
 */
@Service
public class ReservationTransactionService {
    private final ReservationIntentRepository intents;
    private final TicketInventoryRepository skus;
    private final UserPurchaseRepository purchases;
    private final ReservationRepository reservations;
    private final OrderRepository orders;
    private final InventoryService inventory;
    private final OutboxService outbox;
    private final WorkflowExecutionService workflow;
    private final MeterRegistry metrics;
    private final RiskDecisionService risk;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.sale.TicketSaleArmory armory;

    public ReservationTransactionService(ReservationIntentRepository intents, TicketInventoryRepository skus,
                                         UserPurchaseRepository purchases, ReservationRepository reservations,
                                         OrderRepository orders, InventoryService inventory, OutboxService outbox,
                                         WorkflowExecutionService workflow, MeterRegistry metrics, RiskDecisionService risk) {
        this.intents = intents;
        this.skus = skus;
        this.purchases = purchases;
        this.reservations = reservations;
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.workflow = workflow;
        this.metrics = metrics;
        this.risk = risk;
    }

    @Transactional
    public Map<String, Object> reserve(String requestId, String session) {
        return reserve(requestId, session, null);
    }

    @Transactional
    public Map<String, Object> reserve(String requestId, String session, Map<String, Object> admission) {
        ReservationIntent r = intents.findByRequestIdForUpdate(requestId);
        if ("COMPLETED".equals(r.getStatus())) return response(r);
        BusinessException.require("REQUESTED".equals(r.getStatus()), "本次预占已失效，请使用新的请求标识");
        if (armory != null) armory.assertCanReserve(session,r.getSkuId(),admission);
        TicketSku sku = skus.findById(r.getSkuId());
        BusinessException.require(sku != null && session.equals(sku.getEventSessionId()), "票档与场次不匹配");
        long result = inventory.reserve(r);
        BusinessException.require(result > 0, "预占失败：" + result + "（停售/限购/售罄/已释放）");
        int q = r.getQuantity();
        BusinessException.require(RequestTimings.measure("reserve.stock_update", () -> skus.tryDecreaseStockForReservation(r)), "票档已停售或售罄");
        purchases.ensureZeroRow(r.getUserId(), r.getSkuId());
        BusinessException.require(purchases.tryIncreaseWithinLimit(r.getUserId(), r.getSkuId(), q, sku.getPurchaseLimit()), "超过用户限购数量");
        Timestamp expires = new Timestamp(System.currentTimeMillis() + 900000);
        reservations.insertHeld(r.getReservationId(), requestId, r.getUserId(), r.getSkuId(), q, expires);
        orders.insertWaitingPayment(r.getOrderId(), r.getReservationId(), r.getUserId(), r.getSkuId(), q,
                sku.getPrice().multiply(BigDecimal.valueOf(q)),
                r.getDeviceId(), r.getIp(), r.getTraceId(), expires);
        // Durable auto-pay in the same transaction as the order.
        outbox.append(r.getOrderId(), "AUTO_PAYMENT");
        // Store suspicion atomically with the order; investigation is scheduled only after ISSUE.
        risk.saveAdmission(r.getOrderId(), admission, r.getDeviceId(), r.getIp());
        intents.markCompleted(requestId);
        workflow.record(r.getOrderId(), Objects.toString(r.getTraceId(), ""), "RESERVE", r.getReservationId());
        workflow.record(r.getOrderId(), Objects.toString(r.getTraceId(), ""), "ORDER_CREATED", r.getOrderId());
        metrics.counter("order.created").increment();
        return response(r);
    }

    private Map<String, Object> response(ReservationIntent r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", r.getReservationId());
        result.put("orderId", r.getOrderId());
        result.put("status", orders.findStatusById(r.getOrderId()));
        return result;
    }
}
